/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.server.services.dmn;

import java.util.List;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.kie.api.runtime.KieSession;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.api.core.ast.DecisionNode;
import org.kie.dmn.api.core.ast.DecisionServiceNode;
import org.kie.dmn.api.core.ast.InputDataNode;
import org.kie.dmn.core.ast.InputDataNodeImpl;
import org.kie.dmn.core.ast.ItemDefNodeImpl;
import org.kie.dmn.core.internal.utils.DMNEvaluationUtils;
import org.kie.dmn.core.internal.utils.DMNEvaluationUtils.DMNEvaluationResult;
import org.kie.dmn.model.api.InputData;
import org.kie.dmn.model.api.ItemDefinition;
import org.kie.server.api.model.ServiceResponse;
import org.kie.server.api.model.dmn.DMNContextKS;
import org.kie.server.api.model.dmn.DMNDecisionInfo;
import org.kie.server.api.model.dmn.DMNDecisionServiceInfo;
import org.kie.server.api.model.dmn.DMNInputDataInfo;
import org.kie.server.api.model.dmn.DMNItemDefinitionInfo;
import org.kie.server.api.model.dmn.DMNModelInfo;
import org.kie.server.api.model.dmn.DMNModelInfoList;
import org.kie.server.api.model.dmn.DMNQNameInfo;
import org.kie.server.api.model.dmn.DMNResultKS;
import org.kie.server.api.model.dmn.DMNUnaryTestsInfo;
import org.kie.server.services.api.KieServerRegistry;
import org.kie.server.services.impl.KieContainerInstanceImpl;
import org.kie.server.services.impl.locator.ContainerLocatorProvider;
import org.kie.server.services.impl.marshal.MarshallerHelper;
import org.kie.server.services.prometheus.PrometheusKieServerExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelEvaluatorServiceBase {

    private static final Logger LOG = LoggerFactory.getLogger( ModelEvaluatorServiceBase.class );

    private KieServerRegistry context;
    private MarshallerHelper marshallerHelper;

    public ModelEvaluatorServiceBase(KieServerRegistry context) {
        this.context = context;
        this.marshallerHelper = new MarshallerHelper(context);
    }
    
    public ServiceResponse<DMNModelInfoList> getModels(String containerId) {
        try {
            KieContainerInstanceImpl kContainer = context.getContainer(containerId, ContainerLocatorProvider.get().getLocator());
            KieSession kieSession = kContainer.getKieContainer().newKieSession();
            DMNRuntime kieRuntime = kieSession.getKieRuntime(DMNRuntime.class);
            
            List<DMNModel> models = kieRuntime.getModels();
            List<DMNModelInfo> result = models.stream().map(ModelEvaluatorServiceBase::modelToInfo).collect(Collectors.toList());
            
            kieSession.dispose();
            return new ServiceResponse<DMNModelInfoList>(
                    ServiceResponse.ResponseType.SUCCESS,
                    "OK models successfully retrieved from container '" + containerId + "'",
                    new DMNModelInfoList( result ) );
        } catch ( Exception e ) {
            LOG.error( "Error retrieving models from container '" + containerId + "'", e );
            return new ServiceResponse<DMNModelInfoList>(
                    ServiceResponse.ResponseType.FAILURE,
                    "Error retrieving models from container '" + containerId + "'" + e.getMessage(),
                    null );
        }
    }
    
    public static DMNModelInfo modelToInfo(DMNModel model) {
        DMNModelInfo res = new DMNModelInfo();
        res.setNamespace(model.getNamespace());
        res.setName(model.getName());
        res.setId(model.getDefinitions().getId());
        res.setDecisions(model.getDecisions().stream().map(ModelEvaluatorServiceBase::decisionToInfo).collect(Collectors.toSet()));
        res.setDecisionServices(model.getDecisionServices().stream().map(ModelEvaluatorServiceBase::decisionServiceToInfo).collect(Collectors.toSet()));
        res.setInputs(model.getInputs().stream().map(ModelEvaluatorServiceBase::inputDataToInfo).collect(Collectors.toSet()));
        res.setItemDefinitions(model.getItemDefinitions().stream().map(id -> itemDefinitionToInfo(((ItemDefNodeImpl) id).getItemDef())).collect(Collectors.toSet()));
        return res;
    }
    
    public static DMNDecisionServiceInfo decisionServiceToInfo(DecisionServiceNode dsNode) {
        DMNDecisionServiceInfo res = new DMNDecisionServiceInfo();
        res.setName(dsNode.getName());
        res.setId(dsNode.getId());
        return res;
    }

    public static DMNDecisionInfo decisionToInfo(DecisionNode decisionNode) {
        DMNDecisionInfo res = new DMNDecisionInfo();
        res.setName(decisionNode.getName());
        res.setId(decisionNode.getId());
        return res;
    }
    
    public static DMNInputDataInfo inputDataToInfo(InputDataNode inputDataNode) {
        DMNInputDataInfo res = new DMNInputDataInfo();
        res.setName(inputDataNode.getName());
        res.setId(inputDataNode.getId());
        InputData id = ((InputDataNodeImpl) inputDataNode).getInputData();
        QName typeRef = id.getVariable().getTypeRef();
        // for InputData sometimes the NS is not really valorized inside the jdk QName as internally ns are resolved by prefix directly.
        if (typeRef != null) {
            if (XMLConstants.NULL_NS_URI.equals(typeRef.getNamespaceURI())) {
                String actualNS = id.getNamespaceURI(typeRef.getPrefix());
                typeRef = new QName(actualNS, typeRef.getLocalPart(), typeRef.getPrefix());
            }
            res.setTypeRef(DMNQNameInfo.of(typeRef));
        }
        return res;
    }
    
    public static DMNItemDefinitionInfo itemDefinitionToInfo(ItemDefinition itemDef) {
        DMNItemDefinitionInfo res = new DMNItemDefinitionInfo();
        res.setId(itemDef.getId());
        res.setName(itemDef.getName());
        if (itemDef.getTypeRef() != null) {
            res.setTypeRef(DMNQNameInfo.of(itemDef.getTypeRef()));
        }
        if (itemDef.getAllowedValues() != null) {
            DMNUnaryTestsInfo av = new DMNUnaryTestsInfo();
            av.setText(itemDef.getAllowedValues().getText());
            av.setExpressionLanguage(itemDef.getAllowedValues().getExpressionLanguage());
            res.setAllowedValues(av);
        }
        if (itemDef.getItemComponent() != null && !itemDef.getItemComponent().isEmpty()) {
            List<DMNItemDefinitionInfo> components = itemDef.getItemComponent().stream().map(ModelEvaluatorServiceBase::itemDefinitionToInfo).collect(Collectors.toList());
            res.setItemComponent(components);
        }
        res.setTypeLanguage(itemDef.getTypeLanguage());
        res.setIsCollection(itemDef.isIsCollection());
        return res;
    }


    public ServiceResponse<DMNResultKS> evaluateDecisions(String containerId, String contextPayload, String marshallingType) {
        try {
            KieContainerInstanceImpl kContainer = context.getContainer(containerId, ContainerLocatorProvider.get().getLocator());
            KieSession kieSession = kContainer.getKieContainer().newKieSession();
            DMNRuntime dmnRuntime = kieSession.getKieRuntime(DMNRuntime.class);

            PrometheusKieServerExtension extension = (PrometheusKieServerExtension)context.getServerExtension(PrometheusKieServerExtension.EXTENSION_NAME);
            if (extension != null) {
                extension.getDMNRuntimeListeners(kContainer).forEach(l -> dmnRuntime.addListener(l));
            }

            LOG.debug("Will deserialize payload: {}", contextPayload);
            DMNContextKS evalCtx = marshallerHelper.unmarshal(containerId, contextPayload, marshallingType, DMNContextKS.class);
            
            DMNEvaluationResult evaluationResult = DMNEvaluationUtils.evaluate(dmnRuntime,
                                                                               evalCtx.getNamespace(),
                                                                               evalCtx.getModelName(),
                                                                               evalCtx.getDmnContext(),
                                                                               evalCtx.getDecisionNames(),
                                                                               evalCtx.getDecisionIds(),
                                                                               evalCtx.getDecisionServiceName());

            DMNResultKS res = new DMNResultKS(evaluationResult.model.getNamespace(),
                                              evaluationResult.model.getName(),
                                              evalCtx.getDecisionNames(),
                                              evaluationResult.result);
            
            kieSession.dispose();
            return new ServiceResponse<DMNResultKS>(
                    ServiceResponse.ResponseType.SUCCESS,
                    "OK from container '" + containerId + "'",
                    res );
        } catch ( Exception e ) {
            e.printStackTrace();
            LOG.error( "Error from container '" + containerId + "'", e );
            return new ServiceResponse<DMNResultKS>(
                    ServiceResponse.ResponseType.FAILURE,
                    "Error from container '" + containerId + "'" + e.getMessage(),
                    null );
        }
    }

    public KieServerRegistry getKieServerRegistry() {
        return this.context;
    }

}
