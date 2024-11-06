/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.termvectors;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.single.shard.TransportSingleShardAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.termvectors.TermVectorsService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Performs the get operation.
 */
public class TransportTermVectorsAction extends TransportSingleShardAction<TermVectorsRequest, TermVectorsResponse> {

    private final IndicesService indicesService;
    private final ProjectResolver projectResolver;

    @Inject
    public TransportTermVectorsAction(
        ClusterService clusterService,
        TransportService transportService,
        IndicesService indicesService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ProjectResolver projectResolver,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            TermVectorsAction.NAME,
            threadPool,
            clusterService,
            transportService,
            actionFilters,
            indexNameExpressionResolver,
            TermVectorsRequest::new,
            threadPool.executor(ThreadPool.Names.GET)
        );
        this.indicesService = indicesService;
        this.projectResolver = projectResolver;
    }

    @Override
    protected ShardIterator shards(ClusterState state, InternalRequest request) {
        ProjectState project = projectResolver.getProjectState(state);
        if (request.request().doc() != null && request.request().routing() == null) {
            // artificial document without routing specified, ignore its "id" and use either random shard or according to preference
            GroupShardsIterator<ShardIterator> groupShardsIter = clusterService.operationRouting()
                .searchShards(project, new String[] { request.concreteIndex() }, null, request.request().preference());
            return groupShardsIter.iterator().next();
        }

        ShardIterator shards = clusterService.operationRouting()
            .getShards(
                project,
                request.concreteIndex(),
                request.request().id(),
                request.request().routing(),
                request.request().preference()
            );
        return clusterService.operationRouting().useOnlyPromotableShardsForStateless(shards);
    }

    @Override
    protected boolean resolveIndex(TermVectorsRequest request) {
        return true;
    }

    @Override
    protected void resolveRequest(ClusterState state, InternalRequest request) {
        // update the routing (request#index here is possibly an alias or a parent)
        request.request()
            .routing(state.metadata().getProject().resolveIndexRouting(request.request().routing(), request.request().index()));
    }

    @Override
    protected void asyncShardOperation(TermVectorsRequest request, ShardId shardId, ActionListener<TermVectorsResponse> listener)
        throws IOException {
        IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
        IndexShard indexShard = indexService.getShard(shardId.id());
        if (request.realtime()) { // it's a realtime request which is not subject to refresh cycles
            super.asyncShardOperation(request, shardId, listener);
        } else {
            indexShard.ensureShardSearchActive(b -> {
                try {
                    super.asyncShardOperation(request, shardId, listener);
                } catch (Exception ex) {
                    listener.onFailure(ex);
                }
            });
        }
    }

    @Override
    protected TermVectorsResponse shardOperation(TermVectorsRequest request, ShardId shardId) {
        IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
        IndexShard indexShard = indexService.getShard(shardId.id());
        return TermVectorsService.getTermVectors(indexShard, request);
    }

    @Override
    protected Writeable.Reader<TermVectorsResponse> getResponseReader() {
        return TermVectorsResponse::new;
    }

    @Override
    protected Executor getExecutor(TermVectorsRequest request, ShardId shardId) {
        IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
        return indexService.getIndexSettings().isSearchThrottled()
            ? threadPool.executor(ThreadPool.Names.SEARCH_THROTTLED)
            : super.getExecutor(request, shardId);
    }
}
