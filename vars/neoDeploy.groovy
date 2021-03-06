import com.sap.piper.Utils

import com.sap.piper.ConfigurationLoader
import com.sap.piper.ConfigurationMerger
import com.sap.piper.ConfigurationType
import com.sap.piper.tools.ToolDescriptor


def call(parameters = [:]) {

    def stepName = 'neoDeploy'

    Set parameterKeys = [
        'applicationName',
        'archivePath',
        'account',
        'deployAccount', //deprecated, replaced by parameter 'account'
        'deployHost', //deprecated, replaced by parameter 'host'
        'deployMode',
        'dockerEnvVars',
        'dockerImage',
        'dockerOptions',
        'host',
        'neoCredentialsId',
        'neoHome',
        'propertiesFile',
        'runtime',
        'runtimeVersion',
        'vmSize',
        'warAction'
        ]

    Set stepConfigurationKeys = [
        'account',
        'dockerEnvVars',
        'dockerImage',
        'dockerOptions',
        'host',
        'neoCredentialsId',
        'neoHome'
        ]

    handlePipelineStepErrors (stepName: stepName, stepParameters: parameters) {

        def script = parameters?.script ?: [commonPipelineEnvironment: commonPipelineEnvironment]

        def utils = new Utils()

        prepareDefaultValues script: script

        final Map stepConfiguration = [:]

        // Backward compatibility: ensure old configuration is taken into account
        // The old configuration in not stage / step specific

        def defaultDeployHost = script.commonPipelineEnvironment.getConfigProperty('DEPLOY_HOST')
        if(defaultDeployHost) {
            echo "[WARNING][${stepName}] A deprecated configuration framework is used for configuring parameter 'DEPLOY_HOST'. This configuration framework will be removed in future versions."
            stepConfiguration.put('host', defaultDeployHost)
        }

        def defaultDeployAccount = script.commonPipelineEnvironment.getConfigProperty('CI_DEPLOY_ACCOUNT')
        if(defaultDeployAccount) {
            echo "[WARNING][${stepName}] A deprecated configuration framework is used for configuring parameter 'DEPLOY_ACCOUNT'. This configuration framekwork will be removed in future versions."
            stepConfiguration.put('account', defaultDeployAccount)
        }

        if(parameters.deployHost && !parameters.host) {
            echo "[WARNING][${stepName}] Deprecated parameter 'deployHost' is used. This will not work anymore in future versions. Use parameter 'host' instead."
            parameters.put('host', parameters.deployHost)
        }

        if(parameters.deployAccount && !parameters.account) {
            echo "[WARNING][${stepName}] Deprecated parameter 'deployAccount' is used. This will not work anymore in future versions. Use parameter 'account' instead."
            parameters.put('account', parameters.deployAccount)
        }

        def credId = script.commonPipelineEnvironment.getConfigProperty('neoCredentialsId')

        if(credId && !parameters.neoCredentialsId) {
            echo "[WARNING][${stepName}] Deprecated parameter 'neoCredentialsId' from old configuration framework is used. This will not work anymore in future versions."
            parameters.put('neoCredentialsId', credId)
        }

        // Backward compatibility end

        stepConfiguration.putAll(ConfigurationLoader.stepConfiguration(script, stepName))

        Map configuration = ConfigurationMerger.merge(parameters, parameterKeys,
                                                      stepConfiguration, stepConfigurationKeys,
                                                      ConfigurationLoader.defaultStepConfiguration(script, stepName))

        def archivePath = configuration.archivePath
        if(archivePath?.trim()) {
            if (!fileExists(archivePath)) {
                error "Archive cannot be found with parameter archivePath: '${archivePath}'."
            }
        } else {
            error "Archive path not configured (parameter \"archivePath\")."
        }

        def deployHost
        def deployAccount
        def credentialsId = configuration.get('neoCredentialsId')
        def deployMode = configuration.deployMode
        def warAction
        def propertiesFile
        def applicationName
        def runtime
        def runtimeVersion
        def vmSize

        if (deployMode != 'mta' && deployMode != 'warParams' && deployMode != 'warPropertiesFile') {
            throw new Exception("[neoDeploy] Invalid deployMode = '${deployMode}'. Valid 'deployMode' values are: 'mta', 'warParams' and 'warPropertiesFile'")
        }

        if (deployMode == 'warPropertiesFile' || deployMode == 'warParams') {
            warAction = utils.getMandatoryParameter(configuration, 'warAction')
            if (warAction != 'deploy' && warAction != 'rolling-update') {
                throw new Exception("[neoDeploy] Invalid warAction = '${warAction}'. Valid 'warAction' values are: 'deploy' and 'rolling-update'.")
            }
        }

        if (deployMode == 'warPropertiesFile') {
            propertiesFile = utils.getMandatoryParameter(configuration, 'propertiesFile')
            if (!fileExists(propertiesFile)){
                error "Properties file cannot be found with parameter propertiesFile: '${propertiesFile}'."
            }
        }

        if (deployMode == 'warParams') {
            applicationName = utils.getMandatoryParameter(configuration, 'applicationName')
            runtime = utils.getMandatoryParameter(configuration, 'runtime')
            runtimeVersion = utils.getMandatoryParameter(configuration, 'runtimeVersion')
            vmSize = configuration.vmSize
            if (vmSize != 'lite' && vmSize !='pro' && vmSize != 'prem' && vmSize != 'prem-plus') {
                throw new Exception("[neoDeploy] Invalid vmSize = '${vmSize}'. Valid 'vmSize' values are: 'lite', 'pro', 'prem' and 'prem-plus'.")
            }
        }

        if (deployMode.equals('mta') || deployMode.equals('warParams')) {
            deployHost = utils.getMandatoryParameter(configuration, 'host')
            deployAccount = utils.getMandatoryParameter(configuration, 'account')
        }

        def neo = new ToolDescriptor('SAP Cloud Platform Console Client', 'NEO_HOME', 'neoHome', '/tools/', 'neo.sh', '3.39.10', 'version')
        def neoExecutable = neo.getToolExecutable(this, configuration)
        def neoDeployScript

        if (deployMode == 'mta') {
            neoDeployScript =
                """#!/bin/bash
                    "${neoExecutable}" deploy-mta \
                    --host '${deployHost}' \
                    --account '${deployAccount}' \
                    --synchronous"""
        }

        if (deployMode == 'warParams') {
            neoDeployScript =
                """#!/bin/bash
                    "${neoExecutable}" ${warAction} \
                    --host '${deployHost}' \
                    --account '${deployAccount}' \
                    --application '${applicationName}' \
                    --runtime '${runtime}' \
                    --runtime-version '${runtimeVersion}' \
                    --size '${vmSize}'"""
        }

        if (deployMode == 'warPropertiesFile') {
            neoDeployScript =
                """#!/bin/bash
                    "${neoExecutable}" ${warAction} \
                    ${propertiesFile}"""
        }

        withCredentials([usernamePassword(
            credentialsId: credentialsId,
            passwordVariable: 'password',
            usernameVariable: 'username')]) {

            def commonDeployParams =
                """--user '${username}' \
                   --password '${password}' \
                   --source "${archivePath}" \
                """
            dockerExecute(dockerImage: configuration.get('dockerImage'),
                          dockerEnvVars: configuration.get('dockerEnvVars'),
                          dockerOptions: configuration.get('dockerOptions')) {

                neo.verify(this, configuration)

                def java = new ToolDescriptor('Java', 'JAVA_HOME', '', '/bin/', 'java', '1.8.0', '-version 2>&1')
                java.verify(this, configuration)

                sh """${neoDeployScript} \
                      ${commonDeployParams}
                   """
            }
        }
    }
}
