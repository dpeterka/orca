/*
 * Copyright 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.gce

import com.netflix.spinnaker.orca.clouddriver.tasks.ServerGroupCreator
import com.netflix.spinnaker.orca.pipeline.model.Stage
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Component

@Slf4j
@Component
class GoogleServerGroupCreator implements ServerGroupCreator {

  boolean katoResultExpected = false
  String cloudProvider = "gce"

  @Override
  List<Map> getOperations(Stage stage) {
    def operation = [:]

    // If this stage was synthesized by a parallel deploy stage, the operation properties will be under 'cluster'.
    if (stage.context.containsKey("cluster")) {
      operation.putAll(stage.context.cluster as Map)
    } else {
      operation.putAll(stage.context)
    }

    if (operation.account && !operation.credentials) {
      operation.credentials = operation.account
    }

    // If this is a stage in a pipeline, look in the context for the baked image.
    def deploymentDetails = (stage.context.deploymentDetails ?: []) as List<Map>

    if (!operation.image && deploymentDetails) {
      // Bakery ops are keyed off cloudProviderType
      operation.image = deploymentDetails.find { it.cloudProviderType == 'gce' }?.ami

      // Alternatively, FindImage ops distinguish between server groups deployed to different zones.
      // This is partially because AWS images are only available regionally.
      if (!operation.image && stage.context.zone) {
        operation.image = deploymentDetails.find { it.zone == stage.context.zone }?.imageId
      }
    }

    return [[(ServerGroupCreator.OPERATION): operation]]
  }
}
