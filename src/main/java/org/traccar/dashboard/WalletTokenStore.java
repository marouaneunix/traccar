/*
 * Copyright 2026 Traccar contributors
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
 */
package org.traccar.dashboard;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Attribute;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

@Singleton
public class WalletTokenStore {

    public static final String ATTRIBUTE_NAME = "wallet_token";

    private final Config config;
    private final Storage storage;

    @Inject
    public WalletTokenStore(Config config, Storage storage) {
        this.config = config;
        this.storage = storage;
    }

    public static boolean isTokenAttribute(Attribute attribute) {
        return attribute != null && ATTRIBUTE_NAME.equals(attribute.getAttribute());
    }

    public String getToken() throws StorageException {
        try (var attributes = storage.getObjectsStream(Attribute.class, new Request(
                new Columns.All(), new Condition.Equals("attribute", ATTRIBUTE_NAME)))) {
            var matches = attributes.limit(2).toList();
            if (matches.size() > 1) {
                throw new StorageException("walletTokenDuplicate");
            }
            if (!matches.isEmpty()) {
                try {
                    return parseToken(matches.get(0));
                } catch (IllegalArgumentException e) {
                    throw new StorageException("walletTokenAttributeInvalid");
                }
            }
        }
        String token = config.getString(Keys.WALLET_TOKEN);
        return token != null && !token.isBlank() ? token.trim() : null;
    }

    public static String parseToken(Attribute attribute) {
        if (!"string".equals(attribute.getType()) || attribute.getExpression() == null) {
            throw new IllegalArgumentException("walletTokenAttributeInvalid");
        }
        String expression = attribute.getExpression().trim();
        if (expression.length() < 3) {
            throw new IllegalArgumentException("walletTokenAttributeInvalid");
        }
        char quote = expression.charAt(0);
        if ((quote != '\'' && quote != '"') || expression.charAt(expression.length() - 1) != quote) {
            throw new IllegalArgumentException("walletTokenAttributeInvalid");
        }
        // Read only a constant string; never execute token expressions or access position context.
        var token = new StringBuilder();
        for (int index = 1; index < expression.length() - 1; index++) {
            char value = expression.charAt(index);
            if (value == '\\') {
                if (++index >= expression.length() - 1) {
                    throw new IllegalArgumentException("walletTokenAttributeInvalid");
                }
                value = expression.charAt(index);
                if (value != quote && value != '\\') {
                    throw new IllegalArgumentException("walletTokenAttributeInvalid");
                }
            } else if (value == quote) {
                throw new IllegalArgumentException("walletTokenAttributeInvalid");
            }
            if (value <= ' ' || value > '~') {
                throw new IllegalArgumentException("walletTokenAttributeInvalid");
            }
            token.append(value);
        }
        if (token.isEmpty()) {
            throw new IllegalArgumentException("walletTokenAttributeInvalid");
        }
        return token.toString();
    }
}
