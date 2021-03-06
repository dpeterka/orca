package com.netflix.spinnaker.orca.pipeline.persistence.jedis

import com.netflix.spinnaker.orca.batch.StageBuilder
import com.netflix.spinnaker.orca.pipeline.LinearStage
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository.ExecutionCriteria
import com.netflix.spinnaker.orca.pipeline.util.StageNavigator
import redis.clients.jedis.Response

import java.util.function.Function
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.orca.ExecutionStatus
import com.netflix.spinnaker.orca.config.OrcaConfiguration
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper
import com.netflix.spinnaker.orca.pipeline.model.*
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionNotFoundException
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisCommands
import redis.clients.util.Pool
import rx.Observable
import rx.Scheduler
import rx.functions.Func1
import rx.schedulers.Schedulers
import static com.google.common.base.Predicates.notNull
import static com.google.common.collect.Maps.filterValues
import static java.lang.System.currentTimeMillis

@Component
@Slf4j
@CompileStatic
class JedisExecutionRepository implements ExecutionRepository {

  private static final TypeReference<List<Task>> LIST_OF_TASKS = new TypeReference<List<Task>>() {}
  private static final TypeReference<Map<String, Object>> MAP_STRING_TO_OBJECT = new TypeReference<Map<String, Object>>() {
  }
  private final Pool<Jedis> jedisPool
  private final ObjectMapper mapper = new OrcaObjectMapper()
  private final int chunkSize
  private final Scheduler queryAllScheduler
  private final Scheduler queryByAppScheduler

  @Autowired
  StageNavigator stageNavigator

  @Autowired
  JedisExecutionRepository(
    Registry registry,
    Pool<Jedis> jedisPool,
    @Value('${threadPool.executionRepository:150}') int threadPoolSize,
    @Value('${chunkSize.executionRepository:75}') int threadPoolChunkSize
  ) {
    this(
      jedisPool,
      Schedulers.from(newFixedThreadPool(registry, 10, "QueryAll")),
      Schedulers.from(newFixedThreadPool(registry, threadPoolSize, "QueryByApp")),
      threadPoolChunkSize
    )
  }

  JedisExecutionRepository(
    Pool<Jedis> jedisPool,
    Scheduler queryAllScheduler,
    Scheduler queryByAppScheduler,
    int threadPoolChunkSize
  ) {
    this.jedisPool = jedisPool
    this.queryAllScheduler = queryAllScheduler
    this.queryByAppScheduler = queryByAppScheduler
    this.chunkSize = threadPoolChunkSize
  }

  @Override
  void store(Orchestration orchestration) {
    withJedis { Jedis jedis ->
      storeExecutionInternal(jedis, orchestration)
    }
  }

  @Override
  void store(Pipeline pipeline) {
    withJedis { Jedis jedis ->
      storeExecutionInternal(jedis, pipeline)
      jedis.zadd(executionsByPipelineKey(pipeline.pipelineConfigId), pipeline.buildTime, pipeline.id)
    }
  }

  @Override
  void storeExecutionContext(String id, Map<String, Object> context) {
    withJedis { Jedis jedis ->
      String key
      if (jedis.exists("pipeline:$id")) {
        key = "pipeline:$id"
      } else if (jedis.exists("orchestration:$id")) {
        key = "orchestration:$id"
      } else {
        throw new ExecutionNotFoundException("No execution found with id $id")
      }

      jedis.hset(key, "context", mapper.writeValueAsString(context))
    }
  }

  @Override
  void cancel(String id) {
    withJedis { Jedis jedis ->
      String key
      if (jedis.exists("pipeline:$id")) {
        key = "pipeline:$id"
      } else if (jedis.exists("orchestration:$id")) {
        key = "orchestration:$id"
      } else {
        throw new ExecutionNotFoundException("No execution found with id $id")
      }
      if (isNewSchemaVersion(jedis, key)) {
        def data = [canceled: "true"]
        def currentStatus = ExecutionStatus.valueOf(jedis.hget(key, "status"))
        if (currentStatus == ExecutionStatus.NOT_STARTED) {
          data.status = ExecutionStatus.CANCELED.name()
        }
        jedis.hmset(key, data)
      } else {
        def data = mapper.readValue(jedis.hget(key, "config"), Map)
        data.canceled = "true"
        jedis.hset(key, "config", mapper.writeValueAsString(data))
      }
    }
  }

  @Override
  boolean isCanceled(String id) {
    withJedis { Jedis jedis ->
      String key
      if (jedis.exists("pipeline:$id")) {
        key = "pipeline:$id"
      } else if (jedis.exists("orchestration:$id")) {
        key = "orchestration:$id"
      } else {
        throw new ExecutionNotFoundException("No execution found with id $id")
      }
      if (isNewSchemaVersion(jedis, key)) {
        Boolean.valueOf(jedis.hget(key, "canceled"))
      } else {
        def data = mapper.readValue(jedis.hget(key, "config"), Map)
        Boolean.valueOf(data.canceled.toString())
      }
    }
  }

  @Override
  void updateStatus(String id, ExecutionStatus status) {
    withJedis {Jedis jedis->
      String key
      if (jedis.exists("pipeline:$id")) {
        key = "pipeline:$id"
      } else if (jedis.exists("orchestration:$id")) {
        key = "orchestration:$id"
      } else {
        throw new ExecutionNotFoundException("No execution found with id $id")
      }
      if (isNewSchemaVersion(jedis, key)) {
        Map<String, String> map = [status: status.name()]
        if (status == ExecutionStatus.RUNNING) {
          map.startTime = String.valueOf(currentTimeMillis())
        } else if (status.complete) {
          map.endTime = String.valueOf(currentTimeMillis())
        }
        jedis.hmset(key, map)
      } else {
        // TODO: is a no-op the right thing here? Old version derives status from stages.
      }
    }
  }

  boolean isNewSchemaVersion(JedisCommands jedis, String key) {
    !jedis.hexists(key, "config")
  }

  @Override
  void storeStage(PipelineStage stage) {
    withJedis { Jedis jedis ->
      storeStageInternal(jedis, Pipeline, stage)
    }
  }

  @Override
  void storeStage(Stage stage) {
    if (stage instanceof OrchestrationStage) {
      storeStage((OrchestrationStage) stage)
    } else {
      storeStage((PipelineStage) stage)
    }
  }

  @Override
  void storeStage(OrchestrationStage stage) {
    withJedis { Jedis jedis ->
      storeStageInternal(jedis, Orchestration, stage)
    }
  }

  @Override
  Pipeline retrievePipeline(String id) {
    withJedis { Jedis jedis ->
      retrieveInternal(jedis, Pipeline, id)
    }
  }

  @Override
  void deletePipeline(String id) {
    withJedis { Jedis jedis ->
      deleteInternal(jedis, Pipeline, id)
    }
  }

  @Override
  Observable<Pipeline> retrievePipelines() {
    all(Pipeline)
  }

  @Override
  Observable<Pipeline> retrievePipelinesForApplication(String application) {
    allForApplication(Pipeline, application)
  }

  @Override
  @CompileDynamic
  Observable<Pipeline> retrievePipelinesForPipelineConfigId(String pipelineConfigId,
                                                            ExecutionCriteria criteria) {
    def filteredPipelineIds = null as List<String>
    if (criteria.statuses) {
      filteredPipelineIds = []
      withJedis { Jedis jedis ->
        def pipelineKeys = jedis.zrevrange(executionsByPipelineKey(pipelineConfigId), 0, -1)
        def allowedExecutionStatuses = criteria.statuses*.toString() as Set<String>

        def pipeline = jedis.pipelined()
        def fetches = pipelineKeys.collect { pipeline.hget("pipeline:${it}" as String, "status") }
        pipeline.sync()

        fetches.eachWithIndex { Response<String> entry, int index ->
          if (allowedExecutionStatuses.contains(entry.get())) {
            filteredPipelineIds << pipelineKeys[index]
          }
        }
      }

      filteredPipelineIds = filteredPipelineIds.subList(0, Math.min(criteria.limit, filteredPipelineIds.size()))
    }

    return retrieveObservable(Pipeline, executionsByPipelineKey(pipelineConfigId), new Func1<String, Iterable<String>>() {
      @Override
      Iterable<String> call(String key) {
        withJedis { Jedis jedis ->
          return filteredPipelineIds != null ? filteredPipelineIds : jedis.zrevrange(key, 0, (criteria.limit - 1))
        }
      }
    }, queryByAppScheduler)
  }

  @Override
  Orchestration retrieveOrchestration(String id) {
    withJedis { Jedis jedis ->
      retrieveInternal(jedis, Orchestration, id)
    }
  }

  @Override
  void deleteOrchestration(String id) {
    withJedis { Jedis jedis ->
      deleteInternal(jedis, Orchestration, id)
    }
  }

  @Override
  Observable<Orchestration> retrieveOrchestrations() {
    all(Orchestration)
  }

  @Override
  @CompileDynamic
  Observable<Orchestration> retrieveOrchestrationsForApplication(String application, ExecutionCriteria criteria) {
    def allOrchestrationsKey = appKey(Orchestration, application)
    def filteredOrchestrationIds = null as List<String>
    if (criteria.statuses) {
      filteredOrchestrationIds = []
      withJedis { Jedis jedis ->
        def orchestrationKeys = jedis.smembers(allOrchestrationsKey) as List<String>
        def allowedExecutionStatuses = criteria.statuses*.toString() as Set<String>

        def pipeline = jedis.pipelined()
        def fetches = orchestrationKeys.collect { pipeline.hget("orchestration:${it}" as String, "status") }
        pipeline.sync()

        fetches.eachWithIndex { Response<String> entry, int index ->
          if (allowedExecutionStatuses.contains(entry.get())) {
            filteredOrchestrationIds << orchestrationKeys[index]
          }
        }
      }
    }

    return retrieveObservable(Orchestration, allOrchestrationsKey, new Func1<String, Iterable<String>>() {
      @Override
      Iterable<String> call(String key) {
        withJedis { Jedis jedis ->
          return filteredOrchestrationIds != null ? filteredOrchestrationIds : jedis.smembers(key)
        }
      }
    }, queryByAppScheduler)
  }

  private void storeExecutionInternal(JedisCommands jedis, Execution execution) {
    def prefix = execution.getClass().simpleName.toLowerCase()

    if (!execution.id) {
      execution.id = UUID.randomUUID().toString()
      jedis.sadd(alljobsKey(execution.getClass()), execution.id)
      def appKey = appKey(execution.getClass(), execution.application)
      jedis.sadd(appKey, execution.id)
    }

    String key = "${prefix}:$execution.id"

    if (!jedis.exists(key) || isNewSchemaVersion(jedis, key)) {
      Map<String, String> map = [
        version          : String.valueOf(execution.version ?: 2),
        application      : execution.application,
        appConfig        : mapper.writeValueAsString(execution.appConfig),
        canceled         : String.valueOf(execution.canceled),
        parallel         : String.valueOf(execution.parallel),
        limitConcurrent  : String.valueOf(execution.limitConcurrent),
        buildTime        : Long.toString(execution.buildTime ?: 0L),
        // TODO: modify these lines once we eliminate dynamic time properties
        startTime        : (execution.executionStartTime ?: execution.startTime)?.toString(),
        endTime          : (execution.executionEndTime ?: execution.endTime)?.toString(),
        executingInstance: execution.executingInstance,
        status           : execution.executionStatus?.name(),
        authentication   : mapper.writeValueAsString(execution.authentication)
      ]
      // TODO: store separately? Seems crazy to be using a hash rather than a set
      map.stageIndex = execution.stages.id.join(",")
      execution.stages.each { stage ->
        map.putAll(serializeStage(stage))
      }
      if (execution instanceof Pipeline) {
        map.name = execution.name
        map.pipelineConfigId = execution.pipelineConfigId
        map.trigger = mapper.writeValueAsString(execution.trigger)
        map.notifications = mapper.writeValueAsString(execution.notifications)
        map.initialConfig = mapper.writeValueAsString(execution.initialConfig)
      } else if (execution instanceof Orchestration) {
        map.description = execution.description
      }

      jedis.hdel(key, "config")
      jedis.hmset(key, filterValues(map, notNull()))
    } else {
      execution.version = 1
      jedis.hset(key, "config", mapper.writeValueAsString(execution))
    }
  }

  private Map<String, String> serializeStage(Stage stage) {
    Map<String, String> map = [:]
    map["stage.${stage.id}.refId".toString()] = stage.refId
    map["stage.${stage.id}.type".toString()] = stage.type
    map["stage.${stage.id}.name".toString()] = stage.name
    map["stage.${stage.id}.startTime".toString()] = stage.startTime?.toString()
    map["stage.${stage.id}.endTime".toString()] = stage.endTime?.toString()
    map["stage.${stage.id}.status".toString()] = stage.status.name()
    map["stage.${stage.id}.initializationStage".toString()] = String.valueOf(stage.initializationStage)
    map["stage.${stage.id}.syntheticStageOwner".toString()] = stage.syntheticStageOwner?.name()
    map["stage.${stage.id}.parentStageId".toString()] = stage.parentStageId
    map["stage.${stage.id}.requisiteStageRefIds".toString()] = stage.requisiteStageRefIds?.join(",")
    map["stage.${stage.id}.scheduledTime".toString()] = String.valueOf(stage.scheduledTime)
    map["stage.${stage.id}.context".toString()] = mapper.writeValueAsString(stage.context)
    map["stage.${stage.id}.tasks".toString()] = mapper.writeValueAsString(stage.tasks)
    return map
  }

  private <T extends Execution> void storeStageInternal(Jedis jedis, Class<T> type, Stage<T> stage) {
    def prefix = type.simpleName.toLowerCase()
    def key = "$prefix:$stage.execution.id"
    jedis.hmset(key, filterValues(serializeStage(stage), notNull()))
  }

  @CompileDynamic
  private <T extends Execution> T retrieveInternal(Jedis jedis, Class<T> type, String id) throws ExecutionNotFoundException {
    def prefix = type.simpleName.toLowerCase()
    def key = "$prefix:$id"
    if (!isNewSchemaVersion(jedis, key)) {
      def json = jedis.hget(key, "config")
      def execution = mapper.readValue(json, type)
      execution.version = 1
      // PATCH to handle https://jira.netflix.com/browse/SPIN-784
      def originalStageCount = execution.stages.size()
      execution.stages = execution.stages.unique({ it.id })
      if (execution.stages.size() != originalStageCount) {
        log.warn(
          "Pipeline ${id} has duplicate stages (original count: ${originalStageCount}, unique count: ${execution.stages.size()})")
      }
      return sortStages(jedis, execution, type)
    } else if (jedis.exists(key)) {
      Map<String, String> map = jedis.hgetAll(key)
      def execution = type.newInstance()
      execution.id = id
      execution.version = Integer.parseInt(map.version ?: "${type instanceof Pipeline ? Pipeline.CURRENT_VERSION : Orchestration.CURRENT_VERSION}")
      execution.application = map.application
      execution.appConfig.putAll(mapper.readValue(map.appConfig, Map))
      execution.context.putAll(map.context ? mapper.readValue(map.context, Map) : [:])
      execution.canceled = Boolean.parseBoolean(map.canceled)
      execution.parallel = Boolean.parseBoolean(map.parallel)
      execution.limitConcurrent = Boolean.parseBoolean(map.limitConcurrent)
      execution.buildTime = map.buildTime?.toLong()
      execution.executionStartTime = map.startTime?.toLong()
      execution.executionEndTime = map.endTime?.toLong()
      execution.executingInstance = map.executingInstance
      execution.executionStatus = map.status ? ExecutionStatus.valueOf(map.status) : null
      execution.authentication = mapper.readValue(map.authentication, Execution.AuthenticationDetails)
      def stageIds = map.stageIndex.tokenize(",")
      stageIds.each { stageId ->
        def stage = execution instanceof Pipeline ? new PipelineStage() : new OrchestrationStage()
        stage.stageNavigator = stageNavigator
        stage.id = stageId
        stage.refId = map["stage.${stageId}.refId".toString()]
        stage.type = map["stage.${stageId}.type".toString()]
        stage.name = map["stage.${stageId}.name".toString()]
        stage.startTime = map["stage.${stageId}.startTime".toString()]?.toLong()
        stage.endTime = map["stage.${stageId}.endTime".toString()]?.toLong()
        stage.status = ExecutionStatus.valueOf(map["stage.${stageId}.status".toString()])
        stage.initializationStage = map["stage.${stageId}.initializationStage".toString()].toBoolean()
        stage.syntheticStageOwner = map["stage.${stageId}.syntheticStageOwner".toString()] ? Stage.SyntheticStageOwner.valueOf(map["stage.${stageId}.syntheticStageOwner".toString()]) : null
        stage.parentStageId = map["stage.${stageId}.parentStageId".toString()]
        stage.requisiteStageRefIds = map["stage.${stageId}.requisiteStageRefIds".toString()]?.tokenize(",")
        stage.scheduledTime = map["stage.${stageId}.scheduledTime".toString()]?.toLong()
        stage.context = mapper.readValue(map["stage.${stageId}.context".toString()], MAP_STRING_TO_OBJECT)
        stage.tasks = mapper.readValue(map["stage.${stageId}.tasks".toString()], LIST_OF_TASKS)
        stage.execution = execution
        execution.stages << stage
      }
      if (execution instanceof Pipeline) {
        execution.name = map.name
        execution.pipelineConfigId = map.pipelineConfigId
        execution.trigger.putAll(mapper.readValue(map.trigger, Map))
        execution.notifications.addAll(mapper.readValue(map.notifications, List))
        execution.initialConfig.putAll(mapper.readValue(map.initialConfig, Map))
      } else if (execution instanceof Orchestration) {
        execution.description = map.description
      }
      return execution
    } else {
      throw new ExecutionNotFoundException("No ${type.simpleName} found for $id")
    }
  }

  @Deprecated
  @CompileDynamic
  private <T extends Execution> T sortStages(JedisCommands jedis, T execution, Class<T> type) {
    List<Stage<T>> reorderedStages = []

    def childStagesByParentStageId = execution.stages.findAll { it.parentStageId != null }.groupBy { it.parentStageId }
    execution.stages.findAll { it.parentStageId == null }.each { Stage<T> parentStage ->
      reorderedStages << parentStage

      def children = childStagesByParentStageId[parentStage.id] ?: []
      while (!children.isEmpty()) {
        def child = children.remove(0)
        children.addAll(0, childStagesByParentStageId[child.id] ?: [])
        reorderedStages << child
      }
    }

    List<Stage<T>> retrievedStages = retrieveStages(jedis, type, reorderedStages.collect { it.id })
    def retrievedStagesById = retrievedStages.findAll { it?.id }.groupBy { it.id } as Map<String, Stage>
    execution.stages = reorderedStages.collect {
      def explicitStage = retrievedStagesById[it.id] ? retrievedStagesById[it.id][0] : it
      explicitStage.execution = execution
      return explicitStage
    }
    return execution
  }

  @Deprecated
  private <T extends Execution> List<Stage<T>> retrieveStages(Jedis jedis, Class<T> type, List<String> ids) {
    def pipeline = jedis.pipelined()
    ids.each { id ->
      pipeline.hget("${type.simpleName.toLowerCase()}:stage:${id}", "config")
    }
    def results = pipeline.syncAndReturnAll()
    return results.collect { it ? mapper.readValue(it as String, Stage) : null }
  }

  private <T extends Execution> void deleteInternal(Jedis jedis, Class<T> type, String id) {
    def prefix = type.simpleName.toLowerCase()
    def key = "$prefix:$id"
    try {
      def application = jedis.hget(key, "application")
      def appKey = appKey(type, application)
      jedis.srem(appKey, id)

      if (type == Pipeline) {
        def pipelineConfigId = jedis.hget(key, "pipelineConfigId")
        jedis.zrem(executionsByPipelineKey(pipelineConfigId), id)
      }
    } catch (ExecutionNotFoundException ignored) {
      // do nothing
    } finally {
      jedis.del(key)
      jedis.srem(alljobsKey(type), id)
    }
  }

  private <T extends Execution> Observable<T> all(Class<T> type) {
    retrieveObservable(type, alljobsKey(type), queryAllScheduler)
  }

  private <T extends Execution> Observable<T> allForApplication(Class<T> type, String application) {
    retrieveObservable(type, appKey(type, application), queryByAppScheduler)
  }

  @CompileDynamic
  private <T extends Execution> Observable<T> retrieveObservable(Class<T> type, String lookupKey, Scheduler scheduler) {
    return retrieveObservable(type, lookupKey, new Func1<String, Iterable<String>>() {
      @Override
      Iterable<String> call(String key) {
        withJedis { Jedis jedis ->
          return jedis.smembers(key)
        }
      }
    }, scheduler)
  }

  @CompileDynamic
  private <T extends Execution> Observable<T> retrieveObservable(Class<T> type, String lookupKey, Func1<String, Iterable<String>> lookupKeyFetcher, Scheduler scheduler) {
    Observable
      .just(lookupKey)
      .flatMapIterable(lookupKeyFetcher)
      .buffer(chunkSize)
      .flatMap { Collection<String> ids ->
      Observable
        .from(ids)
        .flatMap { String executionId ->
        withJedis { Jedis jedis ->
          try {
            return Observable.just(retrieveInternal(jedis, type, executionId))
          } catch (ExecutionNotFoundException ignored) {
            log.info("Execution (${executionId}) does not exist")
            if (jedis.type(lookupKey) == "zset") {
              jedis.zrem(lookupKey, executionId)
            } else {
              jedis.srem(lookupKey, executionId)
            }
          } catch (Exception e) {
            log.error("Failed to retrieve execution '${executionId}', message: ${e.message}", e)
          }
          return Observable.empty()
        }
      }
      .subscribeOn(scheduler)
    }
  }

  private String alljobsKey(Class type) {
    "allJobs:${type.simpleName.toLowerCase()}"
  }

  private String appKey(Class type, String app) {
    "${type.simpleName.toLowerCase()}:app:${app}"
  }

  static String executionsByPipelineKey(String pipelineConfigId) {
    pipelineConfigId = pipelineConfigId ?: "---"
    "pipeline:executions:$pipelineConfigId"
  }

  private <T> T withJedis(Function<Jedis, T> action) {
    jedisPool.resource.withCloseable(action.&apply)
  }

  private static ThreadPoolTaskExecutor newFixedThreadPool(Registry registry,
                                                           int threadPoolSize,
                                                           String threadPoolName) {
    def executor = new ThreadPoolTaskExecutor(maxPoolSize: threadPoolSize, corePoolSize: threadPoolSize)
    executor.afterPropertiesSet()
    return OrcaConfiguration.applyThreadPoolMetrics(registry, executor, threadPoolName)
  }
}
