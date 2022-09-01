/*
 * DISCLAIMER
 *
 * Copyright 2017 ArangoDB GmbH, Cologne, Germany
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
 *
 * Copyright holder is ArangoDB GmbH, Cologne, Germany
 */

package com.arangodb.springframework.transaction;

import com.arangodb.ArangoDBException;
import com.arangodb.ArangoDatabase;
import com.arangodb.entity.StreamTransactionEntity;
import com.arangodb.entity.StreamTransactionStatus;
import com.arangodb.model.StreamTransactionOptions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.lang.Nullable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Transaction object created by {@link ArangoTransactionManager}.
 */
class ArangoTransactionObject implements SmartTransactionObject {

    private static final Log logger = LogFactory.getLog(ArangoTransactionObject.class);

    private final ArangoDatabase database;
    private final ArangoTransactionResource resource;
    private int timeout;
    private StreamTransactionEntity transaction;

    ArangoTransactionObject(ArangoDatabase database, int defaultTimeout, @Nullable ArangoTransactionResource resource) {
        this.database = database;
        this.resource = resource == null ? new ArangoTransactionResource(null, Collections.emptySet(), false) : resource;
        this.timeout = defaultTimeout;
    }

    ArangoTransactionResource getResource() {
        return resource;
    }

    boolean exists() {
        return getStreamTransaction() != null;
    }

    void configure(TransactionDefinition definition) {
        if (definition.getTimeout() != -1) {
            this.timeout = definition.getTimeout();
        }
        if (definition instanceof TransactionAttribute) {
            addCollections(((TransactionAttribute) definition).getLabels());
        }
    }

    ArangoTransactionResource getOrBegin(Collection<String> collections) throws ArangoDBException {
        addCollections(collections);
        if (resource.getStreamTransactionId() != null) {
            return getResource();
        }
        StreamTransactionOptions options = new StreamTransactionOptions()
                .allowImplicit(true)
                .writeCollections(resource.getCollectionNames().toArray(new String[0]))
                .lockTimeout(Math.max(timeout, 0));
        transaction = database.beginStreamTransaction(options);
        resource.setStreamTransactionId(transaction.getId());
        if (logger.isDebugEnabled()) {
            logger.debug("Began stream transaction " + resource.getStreamTransactionId() + " writing collections " + resource.getCollectionNames());
        }
        return getResource();
    }

    void commit() throws ArangoDBException {
        if (isStatus(StreamTransactionStatus.running)) {
            database.commitStreamTransaction(resource.getStreamTransactionId());
        }
    }

    void rollback() throws ArangoDBException {
        if (isStatus(StreamTransactionStatus.running)) {
            database.abortStreamTransaction(resource.getStreamTransactionId());
        }
        setRollbackOnly();
    }

    @Override
    public boolean isRollbackOnly() {
        return resource.isRollbackOnly() || isStatus(StreamTransactionStatus.aborted);
    }

    public void setRollbackOnly() {
        resource.setRollbackOnly(true);
    }

    @Override
    public void flush() {
        TransactionSynchronizationUtils.triggerFlush();
    }

    @Override
    public String toString() {
        return resource.getStreamTransactionId() == null ? "(not begun)" : resource.getStreamTransactionId();
    }

    private void addCollections(Collection<String> collections) {
        if (resource.getStreamTransactionId() != null) {
            if (!resource.getCollectionNames().containsAll(collections) && logger.isDebugEnabled()) {
                Set<String> additional = new HashSet<>(collections);
                additional.removeAll(resource.getCollectionNames());
                logger.debug("Stream transaction already started on collections " + resource.getCollectionNames() + ", assuming additional collections are read only: " + additional);
            }
        } else {
            Set<String> all = new HashSet<>(resource.getCollectionNames());
            all.addAll(collections);
            resource.setCollectionNames(all);
        }
    }

    private boolean isStatus(StreamTransactionStatus status) {
        getStreamTransaction();
        return transaction != null && transaction.getStatus() == status;
    }

    private StreamTransactionEntity getStreamTransaction() {
        if (transaction == null && resource.getStreamTransactionId() != null) {
            transaction = database.getStreamTransaction(resource.getStreamTransactionId());
        }
        return transaction;
    }
}