/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.appservice.util;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TokenCache {

    private static final Logger LOGGER = Logger.getLogger(TokenCache.class.getName());

    private static final Object TSAFE = new Object();

    private static TokenCache cache = null;

    protected final AzureCredentials.ServicePrincipal credentials;

    public static TokenCache getInstance(final AzureCredentials.ServicePrincipal servicePrincipal) {
        synchronized (TSAFE) {
            if (cache == null) {
                cache = new TokenCache(servicePrincipal);
            } else if (cache.credentials == null
                    || !StringUtils.isEmpty(cache.credentials.getSubscriptionId()) || !cache.credentials.getSubscriptionId().equals(servicePrincipal.getSubscriptionId())
                    || !StringUtils.isEmpty(cache.credentials.getClientId()) || !cache.credentials.getClientId().equals(servicePrincipal.getClientId())
                    || !StringUtils.isEmpty(cache.credentials.getClientSecret()) || !cache.credentials.getClientSecret().equals(servicePrincipal.getClientSecret())
                    || !StringUtils.isEmpty(cache.credentials.getTenant()) || !cache.credentials.getTenant().equals(servicePrincipal.getTenant())
                    || !StringUtils.isEmpty(cache.credentials.getServiceManagementURL()) || !cache.credentials.getServiceManagementURL().equals(servicePrincipal.getServiceManagementURL())) {
                cache = new TokenCache(servicePrincipal);
            }
        }

        return cache;
    }

    protected TokenCache(final AzureCredentials.ServicePrincipal servicePrincipal) {
        LOGGER.log(Level.FINEST, "TokenCache: TokenCache: Instantiate new cache manager");
        this.credentials = servicePrincipal;
    }

    public static String getUserAgent() {
        String version = null;
        String instanceId = null;
        try {
            version = TokenCache.class.getPackage().getImplementationVersion();
            Jenkins inst = Jenkins.getInstance();
            if (inst != null) {
                instanceId = inst.getLegacyInstanceId();
            }
        } catch (Exception e) {
        }

        if (version == null) {
            version = "local";
        }
        if (instanceId == null) {
            instanceId = "local";
        }

        return Constants.PLUGIN_NAME + "/" + version + "/" + instanceId;
    }

    public static ApplicationTokenCredentials get(final AzureCredentials.ServicePrincipal servicePrincipal) {
        final AzureEnvironment env = new AzureEnvironment(new HashMap<String, String>() {
            {
                this.put("managementEndpointUrl", servicePrincipal.getServiceManagementURL());
                this.put("resourceManagerEndpointUrl", servicePrincipal.getResourceManagerEndpoint());
                this.put("activeDirectoryEndpointUrl", servicePrincipal.getAuthenticationEndpoint());
                this.put("activeDirectoryGraphResourceId", servicePrincipal.getGraphEndpoint());
            }
        });
        return new ApplicationTokenCredentials(
                servicePrincipal.getClientId(),
                servicePrincipal.getTenant(),
                servicePrincipal.getClientSecret(),
                env
        );
    }

    public Azure getAzureClient() {
        return Azure
                .configure()
                .withLogLevel(Constants.DEFAULT_AZURE_SDK_LOGGING_LEVEL)
                .withUserAgent(getUserAgent())
                .authenticate(get(credentials))
                .withSubscription(credentials.getSubscriptionId());
    }
}
