/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.spring.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.kafka.define.Constants;
import org.apache.skywalking.apm.plugin.kafka.define.KafkaContext;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

public class ExtendedKafkaConsumerInterceptor implements InstanceMethodsAroundInterceptor {

    public static final String OPERATE_NAME_PREFIX = "Kafka/";
    public static final String CONSUMER_OPERATE_NAME = "/Consumer/";

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        // We don't need to do anything in before method for poll
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        /*
         * If the intercepted method throws exception, the ret will be null
         */
        if (ret == null) {
            return ret;
        }

        // Get the returned records
        ConsumerRecords<?, ?> records = (ConsumerRecords<?, ?>) ret;

        //
        // The entry span will only be created when the consumer received at least one message.
        //
        if (records.count() > 0) {
            KafkaContext context = (KafkaContext) ContextManager.getRuntimeContext().get(Constants.KAFKA_FLAG);
            if (context != null) {
                ContextManager.createEntrySpan(context.getOperationName(), null);
                context.setNeedStop(true);
            }

            // Create operation name
            String operationName = OPERATE_NAME_PREFIX + "SpringKafka" + CONSUMER_OPERATE_NAME + "Group";

            AbstractSpan activeSpan = ContextManager.createEntrySpan(operationName, null);

            activeSpan.setComponent(ComponentsDefine.KAFKA_CONSUMER);
            SpanLayer.asMQ(activeSpan);
            Tags.MQ_BROKER.set(activeSpan, "Unknown");
            
            // Get all partitions
            Set<TopicPartition> partitions = records.partitions();
            if (!partitions.isEmpty()) {
                TopicPartition partition = partitions.iterator().next();
                Tags.MQ_TOPIC.set(activeSpan, partition.topic());
            } else {
                Tags.MQ_TOPIC.set(activeSpan, "Unknown");
            }
            activeSpan.setPeer("Unknown");
            
            // Iterate through all records
            for (ConsumerRecord<?, ?> record : records) {
                ContextCarrier contextCarrier = new ContextCarrier();

                CarrierItem next = contextCarrier.items();
                while (next.hasNext()) {
                    next = next.next();
                    Iterator<Header> iterator = record.headers().headers(next.getHeadKey()).iterator();
                    if (iterator.hasNext()) {
                        next.setHeadValue(new String(iterator.next().value(), StandardCharsets.UTF_8));
                    }
                }
                ContextManager.extract(contextCarrier);
            }
            ContextManager.stopSpan();
        }
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        /*
         * The entry span is created in {@link #afterMethod}, but {@link #handleMethodException} is called before
         * {@link #afterMethod}, before the creation of entry span, we can not ensure there is an active span
         */
        if (ContextManager.isActive()) {
            ContextManager.activeSpan().log(t);
        }
    }
}