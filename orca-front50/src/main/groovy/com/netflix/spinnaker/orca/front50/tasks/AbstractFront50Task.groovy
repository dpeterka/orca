/*
 * Copyright 2014 Netflix, Inc.
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


package com.netflix.spinnaker.orca.front50.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.orca.DefaultTaskResult
import com.netflix.spinnaker.orca.ExecutionStatus
import com.netflix.spinnaker.orca.Task
import com.netflix.spinnaker.orca.TaskResult
import com.netflix.spinnaker.orca.front50.Front50Service
import com.netflix.spinnaker.orca.front50.model.Application
import com.netflix.spinnaker.orca.pipeline.model.Stage
import org.springframework.beans.factory.annotation.Autowired
import retrofit.RetrofitError

abstract class AbstractFront50Task implements Task {
  @Autowired
  Front50Service front50Service

  @Autowired
  ObjectMapper mapper

  abstract  Map<String, Object>  performRequest(String account, Application application)
  abstract String getNotificationType()

  @Override
  TaskResult execute(Stage stage) {
    def application = mapper.convertValue(stage.context.application, Application)
    def account = (stage.context.account as String)?.toLowerCase()

    def missingInputs = []
    if (!account) {
      missingInputs << 'account'
    }

    if (!application.name) {
      missingInputs << 'application.name'
    }

    if (missingInputs) {
      throw new IllegalArgumentException("Missing one or more required task parameters (${missingInputs.join(", ")})")
    }


    def outputs = [
      "notification.type": getNotificationType(),
      "application.name": application.name,
      "account": account
    ]
    def executionStatus = ExecutionStatus.SUCCEEDED

    Map<String, Object> results = performRequest(account, application)
    return new DefaultTaskResult(executionStatus, outputs + results)
  }

  Application fetchApplication(String account, String applicationName) {
    try {
      return front50Service.get(account, applicationName)
    } catch (RetrofitError e) {
      if (e.response.status == 404) {
        return null
      }

      throw e
    }
  }
}
