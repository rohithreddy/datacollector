/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.waveanalytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.Error;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import com.sforce.ws.SessionRenewer;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.CsvHeader;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.lib.generator.delimited.DelimitedCharDataGenerator;
import com.streamsets.pipeline.lib.salesforce.ForceConfigBean;
import com.streamsets.pipeline.lib.salesforce.ForceUtils;
import com.streamsets.pipeline.lib.waveanalytics.WaveAnalyticsConfigBean;
import com.streamsets.pipeline.lib.waveanalytics.Errors;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * This target writes records to Salesforce Einstein Analytics
 */
public class WaveAnalyticsTarget extends BaseTarget {

  private static final Logger LOG = LoggerFactory.getLogger(WaveAnalyticsTarget.class);
  private static final String dataflowList = "/insights/internal_api/v1.0/esObject/workflow";
  private static final String dataflowJson = "/insights/internal_api/v1.0/esObject/workflow/%s/json";
  private static final String startDataflow = "/insights/internal_api/v1.0/esObject/workflow/%s/start";
  private static final String CONF_PREFIX = "conf";

  // Status values indicating that the job is done
  private static final List<String> DONE = ImmutableList.of("Completed",
      "CompletedWithWarnings",
      "Failed",
      "NotProcessed"
  );
  private static final String HTTP_PATCH = "PATCH";
  private final WaveAnalyticsConfigBean conf;

  private PartnerConnection connection;
  private String restEndpoint;
  private String datasetID = null;
  private int partNumber = 1;
  private String datasetName = null;
  private HttpClient httpClient;

  public WaveAnalyticsTarget(WaveAnalyticsConfigBean conf) {
    this.conf = conf;
  }

  // Renew the Salesforce session on timeout
  private class WaveSessionRenewer implements SessionRenewer {
    @Override
    public SessionRenewalHeader renewSession(ConnectorConfig config) throws ConnectionException {
      try {
        connection = Connector.newConnection(ForceUtils.getPartnerConfig(conf, new WaveSessionRenewer()));
      } catch (StageException e) {
        throw new ConnectionException("Can't create partner config", e);
      }

      SessionRenewalHeader header = new SessionRenewalHeader();
      header.name = new QName("urn:enterprise.soap.sforce.com", "SessionHeader");
      header.headerElement = connection.getSessionHeader();
      return header;
    }
  }

  private void openDataset() throws ConnectionException, StageException {
    datasetName = conf.edgemartAliasPrefix;
    if (conf.appendTimestamp) {
      datasetName += "_" + System.currentTimeMillis();
    }

    SObject sobj = new SObject();
    sobj.setType("InsightsExternalData");
    sobj.setField("Format", "Csv");
    sobj.setField("EdgemartAlias", datasetName);
    sobj.setField("MetadataJson", conf.metadataJson.getBytes(StandardCharsets.UTF_8));
    sobj.setField("Operation", conf.operation.getLabel());
    sobj.setField("Action", "None");
    if (!StringUtils.isEmpty(conf.edgemartContainer)) {
      sobj.setField("EdgemartContainer", conf.edgemartContainer);
    }

    SaveResult[] results = connection.create(new SObject[]{sobj});
    for (SaveResult sv : results) {
      if (sv.isSuccess()) {
        datasetID = sv.getId();
        partNumber = 1;
        LOG.info("Success creating InsightsExternalData: " + datasetID);
      } else {
        for (Error e : sv.getErrors()) {
          throw new StageException(Errors.WAVE_01, e.getMessage());
        }
      }
    }
  }

  private void writeToDataset(Batch batch) throws StageException {
    StringWriter writer = new StringWriter();
    DataGenerator gen;
    CsvHeader csvHeader = conf.metadataJson.isEmpty() ? CsvHeader.WITH_HEADER : CsvHeader.NO_HEADER;
    try {
      gen = new DelimitedCharDataGenerator(writer, CsvMode.CSV.getFormat(), csvHeader, "header", "value", null);
    } catch (IOException ioe) {
      throw new StageException(Errors.WAVE_01, ioe);
    }

    Iterator<Record> batchIterator = batch.getRecords();

    while (batchIterator.hasNext()) {
      Record record = batchIterator.next();
      try {
        gen.write(record);
      } catch (Exception e) {
        switch (getContext().getOnErrorRecord()) {
          case DISCARD:
            break;
          case TO_ERROR:
            getContext().toError(record, Errors.WAVE_01, e.toString());
            break;
          case STOP_PIPELINE:
            throw new StageException(Errors.WAVE_01, e.toString());
          default:
            throw new IllegalStateException(Utils.format(
                "Unknown OnError value '{}'",
                getContext().getOnErrorRecord(),
                e
            ));
        }
      }
    }

    try {
      gen.close();
    } catch (IOException ioe) {
      throw new StageException(Errors.WAVE_01, ioe);
    }

    SObject sobj = new SObject();
    sobj.setType("InsightsExternalDataPart");
    sobj.setField("DataFile", writer.toString().getBytes(StandardCharsets.UTF_8));
    sobj.setField("InsightsExternalDataId", datasetID);
    sobj.setField("PartNumber", partNumber);

    partNumber++;

    try {
      SaveResult[] results = connection.create(new SObject[]{sobj});
      for (SaveResult sv : results) {
        if (sv.isSuccess()) {
          String rowId = sv.getId();
          LOG.info("Success creating InsightsExternalDataPart: " + rowId);
        } else {
          for (Error e : sv.getErrors()) {
            throw new StageException(Errors.WAVE_01, e.getMessage());
          }
        }
      }
    } catch (ConnectionException ce) {
      throw new StageException(Errors.WAVE_01, ce);
    }
  }

  private String getDataflowId() throws StageException {
    LOG.info("getDataflowId: " + restEndpoint + dataflowList);

    try {
      String json = httpClient.newRequest(restEndpoint + dataflowList)
          .header("Authorization", "OAuth " + connection.getConfig().getSessionId())
          .send()
          .getContentAsString();

      LOG.info("Got dataflow list: {}", json);

      ObjectMapper mapper = new ObjectMapper();
      DataflowList dataflows = mapper.readValue(json, DataflowList.class);

      for (int i = 0; i < dataflows.result.size(); i++) {
        Result r = dataflows.result.get(i);
        if (r.name.equals(conf.dataflowName)) {
          return r._uid;
        }

      }
    } catch (InterruptedException | TimeoutException | ExecutionException | IOException e ) {
      throw new StageException(Errors.WAVE_02, e.getMessage(), e);
    }

    return null;
  }

  private String getDataflowJson(String dataflowId) throws StageException {
    // Add the dataset to the dataflow
    try {
      return httpClient.newRequest(String.format(restEndpoint + dataflowJson, dataflowId))
          .header("Authorization", "OAuth " + connection.getConfig().getSessionId())
          .send()
          .getContentAsString();
    } catch (InterruptedException | TimeoutException | ExecutionException e ) {
      throw new StageException(Errors.WAVE_03, e.getMessage(), e);
    }
  }

  private void putDataflowJson(String dataflowId, String payload) throws StageException {
    try {
      ContentResponse response = httpClient.newRequest(String.format(restEndpoint + dataflowJson, dataflowId))
          .method(HTTP_PATCH)
          .content(new StringContentProvider(payload), "application/json")
          .header("Authorization", "OAuth " + connection.getConfig().getSessionId())
          .send();
      int statusCode = response.getStatus();
      String content = response.getContentAsString();

      LOG.info("PATCH dataflow with result {} content {}", statusCode, content);
    } catch (InterruptedException | TimeoutException | ExecutionException e) {
      LOG.error("PATCH dataflow with result {}", e.getMessage());
      throw new StageException(Errors.WAVE_03, e.getMessage(), e);
    }
  }

  private void runDataflow(String dataflowId) throws StageException {
    try {
      ContentResponse response = httpClient.newRequest(restEndpoint + String.format(startDataflow, dataflowId))
          .method(HttpMethod.PUT)
          .header("Authorization", "OAuth " + connection.getConfig().getSessionId())
          .send();
      int statusCode = response.getStatus();
      String content = response.getContentAsString();

      LOG.info("PUT dataflow with result {} content {}", statusCode, content);
    } catch (InterruptedException | TimeoutException | ExecutionException e) {
      LOG.error("PUT dataflow with result {}", e.getMessage());
      throw new StageException(Errors.WAVE_03, e.getMessage(), e);
    }
  }

  private String addDatasetToDataflow(String dataflowJson) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> dataflow = mapper.readValue(dataflowJson, new TypeReference<Map<String, Object>>() {
    });

    List<Object> result = (List<Object>) dataflow.get("result");
    Map<String, Object> workflow = null;
    for (int i = 0; i < result.size(); i++) {
      Map<String, Object> w = (Map<String, Object>) result.get(i);
      if (w.get("name").equals(conf.dataflowName)) {
        workflow = w;
        break;
      }
    }
    if (workflow == null) {
      LOG.info("Can't find dataflow " + conf.dataflowName);
      return null;
    }
    // TODO - create dataflow!

    Map<String, Object> workflowDefinition = (Map<String, Object>) workflow.get("workflowDefinition");

    // Make new 'extract' source
    String extractSource = "Extract_" + datasetName;
    Map<String, Object> parameters = new HashMap<String, Object>();
    parameters.put("alias", datasetName);
    Map<String, Object> extractDataset = new HashMap<String, Object>();
    extractDataset.put("action", "edgemart");
    extractDataset.put("parameters", parameters);

    // Look for 'append' job
    String appendJob = "Append_" + conf.edgemartAliasPrefix;
    Map<String, Object> append = (Map<String, Object>) workflowDefinition.get(appendJob);

    if (append == null) {
      LOG.info("Can't find {} in dataflow {}", appendJob, conf.dataflowName);

      String registerJob = "Register_" + conf.edgemartAliasPrefix;

      Map<String, Object> register = (Map<String, Object>) workflowDefinition.get(registerJob);

      if (register == null) {
        // Clear out dataflow and create bits we need
        LOG.info("Can't find {} in dataflow {}", registerJob, conf.dataflowName);
        LOG.info("Clearing out dataflow");

        workflowDefinition = new HashMap<String, Object>();

        parameters = new HashMap<String, Object>();
        parameters.put("alias", conf.edgemartAliasPrefix);
        parameters.put("name", conf.edgemartAliasPrefix);
        parameters.put("source", extractSource);

        register = new HashMap<String, Object>();
        register.put("action", "sfdcRegister");
        register.put("parameters", parameters);

        workflowDefinition.put(registerJob, register);
      } else {
        // Create append job and knit it into the flow
        LOG.info("Creating append job in dataflow {}", conf.dataflowName);

        Map<String, Object> regParams = ((Map<String, Object>)register.get("parameters"));

        parameters = new HashMap<String, Object>();
        parameters.put("sources", Arrays.asList(
            regParams.get("source"),
            extractSource
        ));

        append = new HashMap<String, Object>();
        append.put("action", "append");
        append.put("parameters", parameters);

        workflowDefinition.put(appendJob, append);

        regParams.put("source", appendJob);
      }
    } else {
      // Add 'extract' to 'append' job
      Map<String, Object> parameters2 = (Map<String, Object>) append.get("parameters");
      List<String> sources = (List<String>) parameters2.get("sources");
      sources.add(extractSource);
    }

    workflowDefinition.put(extractSource, extractDataset);

    // Make upload map
    Map<String, Object> map = new HashMap<String, Object>();
    map.put("workflowDefinition", workflowDefinition);

    return mapper.writeValueAsString(map);
  }

  private void commitDataset() throws StageException, ConnectionException, IOException {
    // Instruct Wave to start processing the data
    SObject sobj = new SObject();
    sobj.setType("InsightsExternalData");
    sobj.setField("Action", "Process");
    sobj.setId(datasetID);

    SaveResult[] results = connection.update(new SObject[]{sobj});
    for (SaveResult sv : results) {
      if (sv.isSuccess()) {
        String rowId = sv.getId();
        LOG.info("Success updating InsightsExternalData: {}", rowId);
      } else {
        for (Error e : sv.getErrors()) {
          throw new StageException(Errors.WAVE_01, e.getMessage());
        }
      }
    }

    if (conf.useDataflow) {
      // Poll until the dataset has been processed
      boolean done = false;
      int sleepTime = 1000;
      while (!done) {
        try {
          Thread.sleep(sleepTime);
          sleepTime *= 2;
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
        QueryResult queryResults = connection.query(
            "SELECT Status, StatusMessage FROM InsightsExternalData WHERE Id = '" + datasetID + "'");
        if (queryResults.getSize() > 0) {
          for (SObject s : queryResults.getRecords()) {
            String status = (String) s.getField("Status");
            LOG.info("Dataset status is {}", status);
            if (DONE.contains(status)) {
              done = true;
              String statusMessage = (String) s.getField("StatusMessage");
              if (statusMessage != null) {
                LOG.info("Dataset status message is {}", statusMessage);
              }
            }
          }
        } else {
          LOG.warn("Can't find InsightsExternalData with Id " + datasetID);
        }
      }

      // Add the dataset to the dataflow
      String dataflowId = getDataflowId();
      String dataflowJson = getDataflowJson(dataflowId);

      LOG.info("Got dataflow json: {}", dataflowJson);

      String newDataflowJson = addDatasetToDataflow(dataflowJson);

      LOG.info("Uploading dataflow {}", newDataflowJson);

      putDataflowJson(dataflowId, newDataflowJson);

      if (conf.runDataflow) {
        LOG.info("Running dataflow {}", dataflowId);
        runDataflow(dataflowId);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected List<ConfigIssue> init() {
    // Validate configuration values and open any required resources.
    List<ConfigIssue> issues = super.init();
    Optional
        .ofNullable(conf.init(getContext(), CONF_PREFIX ))
        .ifPresent(issues::addAll);

    try {
      ConnectorConfig partnerConfig = ForceUtils.getPartnerConfig(conf, new WaveSessionRenewer());
      connection = Connector.newConnection(partnerConfig);
      LOG.info("Successfully authenticated as {}", conf.username);
      if (conf.mutualAuth.useMutualAuth) {
        ForceUtils.setupMutualAuth(partnerConfig, conf.mutualAuth);
      }

      String soapEndpoint = connection.getConfig().getServiceEndpoint();
      restEndpoint = soapEndpoint.substring(0, soapEndpoint.indexOf("services/Soap/"));

      httpClient = new HttpClient(ForceUtils.makeSslContextFactory(conf));
      if (conf.useProxy) {
        ForceUtils.setProxy(httpClient, conf);
      }
      httpClient.start();
    } catch (Exception e) {
      LOG.error("Exception during init()", e);
      issues.add(getContext().createConfigIssue(Groups.FORCE.name(),
          ForceConfigBean.CONF_PREFIX + "authEndpoint",
          Errors.WAVE_00,
          ForceUtils.getExceptionCode(e) + ", " + ForceUtils.getExceptionMessage(e)
      ));
    }

    // If issues is not empty, the UI will inform the user of each configuration issue in the list.
    return issues;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void destroy() {
    datasetID = null;
    if (httpClient != null) {
      try {
        httpClient.stop();
      } catch (Exception e) {
        LOG.error("Exception stopping HttpClient", e);
      }
    }

    super.destroy();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void write(Batch batch) throws StageException {
    if (batch.getRecords().hasNext()) {
      if (datasetID == null) {
        LOG.info("Opening dataset");
        try {
          openDataset();
        } catch (ConnectionException ce) {
          throw new StageException(Errors.WAVE_01, ce);
        }
      }

      LOG.info("Writing batch to dataset");
      writeToDataset(batch);
      try {
        commitDataset();
      } catch (ConnectionException | IOException e) {
        throw new StageException(Errors.WAVE_01, e);
      }
    }
  }
}
