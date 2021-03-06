/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.spinnaker.orca.kato.tasks

import com.netflix.spinnaker.orca.clouddriver.KatoService
import com.netflix.spinnaker.orca.clouddriver.model.TaskId
import com.netflix.spinnaker.orca.kato.pipeline.ModifyAsgLaunchConfigurationStage
import com.netflix.spinnaker.orca.pipeline.model.Orchestration
import com.netflix.spinnaker.orca.pipeline.model.OrchestrationStage
import spock.lang.Specification
import spock.lang.Subject

class ModifyAsgLaunchConfigurationTaskSpec extends Specification {

  KatoService katoService = Mock(KatoService)
  @Subject
  ModifyAsgLaunchConfigurationTask task = new ModifyAsgLaunchConfigurationTask(kato: katoService, defaultBakeAccount: 'default')

  void 'should populate deploy.server.groups to enable force cache refresh'() {
    setup:
    def taskConfig = [
      credentials: 'test',
      region     : region,
      asgName    : asgName
    ]
    def stage = new OrchestrationStage(new Orchestration(), ModifyAsgLaunchConfigurationStage.PIPELINE_CONFIG_TYPE, taskConfig)

    when:
    def result = task.execute(stage)

    then:
    1 * katoService.requestOperations('aws', _) >> rx.Observable.just(new TaskId('blerg'))
    result.stageOutputs.'deploy.server.groups' == [(region): [asgName]]

    where:
    region = 'us-east-1'
    asgName = 'myasg-v001'
  }

  void 'should add amiName from deploymentDetails if not specified'() {
    setup:
    def taskConfig = [
      credentials      : 'test',
      region           : region,
      asgName          : asgName,
      deploymentDetails: [
        [ami: deploymentDetailsAmi, region: region]
      ],
      amiName          : contextAmi
    ]
    def stage = new OrchestrationStage(new Orchestration(), ModifyAsgLaunchConfigurationStage.PIPELINE_CONFIG_TYPE, taskConfig)

    when:
    def result = task.execute(stage)

    then:
    1 * katoService.requestOperations('aws', _) >> { cloudProvider, ops ->
      def opConfig = ops.last().modifyAsgLaunchConfigurationDescription

      assert opConfig.amiName == expectedAmi
      rx.Observable.just(new TaskId('blerg'))
    }

    where:
    deploymentDetailsAmi | contextAmi | expectedAmi
    'ami-dd'             | 'ami-cc'   | 'ami-cc'
    'ami-dd'             | null       | 'ami-dd'
    region = 'us-east-1'
    asgName = 'myasg-v001'
  }

  void 'should inject allowLaunch if deploy account does not match bake account'() {
    setup:
    def taskConfig = [
      credentials: credentials,
      region     : region,
      asgName    : asgName,
      amiName    : 'ami-abcdef'
    ]
    def stage = new OrchestrationStage(new Orchestration(), ModifyAsgLaunchConfigurationStage.PIPELINE_CONFIG_TYPE, taskConfig)

    when:
    def result = task.execute(stage)

    then:
    1 * katoService.requestOperations('aws', _) >> { cloudProvider, ops ->
      assert ops.size() == expectedOpsSize
      if (expectedOpsSize == 2) {
        def allowLaunch = ops.first().allowLaunchDescription
        assert allowLaunch.account == credentials
        assert allowLaunch.credentials == 'default'
        assert allowLaunch.region == region
        assert allowLaunch.amiName == 'ami-abcdef'
      }
      rx.Observable.just(new TaskId('blerg'))
    }

    where:
    credentials | expectedOpsSize
    'default'   | 1
    'test'      | 2
    region = 'us-east-1'
    asgName = 'myasg-v001'
  }

}
