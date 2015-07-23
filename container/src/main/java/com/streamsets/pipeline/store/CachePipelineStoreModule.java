/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.store;

import com.streamsets.dc.execution.store.CachePipelineStateStoreModule;
import com.streamsets.pipeline.main.RuntimeModule;
import com.streamsets.pipeline.stagelibrary.StageLibraryModule;
import com.streamsets.pipeline.store.impl.CachePipelineStoreTask;
import com.streamsets.pipeline.store.impl.FilePipelineStoreTask;
import com.streamsets.pipeline.util.LockCache;
import com.streamsets.pipeline.util.LockCacheModule;

import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

@Module(injects = PipelineStoreTask.class, library = true, includes = {RuntimeModule.class, StageLibraryModule.class,
  CachePipelineStateStoreModule.class, LockCacheModule.class})
public class CachePipelineStoreModule {

  @Provides
  @Singleton
  public PipelineStoreTask provideStore(FilePipelineStoreTask store, LockCache<String> lockCache) {
    return new CachePipelineStoreTask(store, lockCache);
  }

}
