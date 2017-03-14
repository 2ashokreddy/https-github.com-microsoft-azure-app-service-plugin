/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.util;

import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.aad.adal4j.ClientCredential;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.microsoft.exceptions.AzureCloudException;

public class TokenCache {

    private static final Logger LOGGER = Logger.getLogger(TokenCache.class.getName());

    private static final Object tsafe = new Object();

    private static TokenCache cache = null;

    protected final String subscriptionId;

    protected final String clientId;

    protected final String clientSecret;

    protected final String oauth2TokenEndpoint;

    protected final String serviceManagementURL;

    private final String path;

    public static TokenCache getInstance(
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL) {

        synchronized (tsafe) {
            if (cache == null) {
                cache = new TokenCache(
                        subscriptionId, clientId, clientSecret, oauth2TokenEndpoint, serviceManagementURL);
            } else if (cache.subscriptionId == null || !cache.subscriptionId.equals(subscriptionId)
                    || cache.clientId == null || !cache.clientId.equals(clientId)
                    || cache.clientSecret == null || !cache.clientSecret.equals(clientSecret)
                    || cache.oauth2TokenEndpoint == null || !cache.oauth2TokenEndpoint.equals(oauth2TokenEndpoint)
                    || cache.serviceManagementURL == null || !cache.serviceManagementURL.equals(serviceManagementURL)) {
                cache.clear();
                cache = new TokenCache(
                        subscriptionId, clientId, clientSecret, oauth2TokenEndpoint, serviceManagementURL);
            }
        }

        return cache;
    }

    private TokenCache(
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL) {
        LOGGER.info("Instantiate new cache manager");

        this.subscriptionId = subscriptionId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.oauth2TokenEndpoint = oauth2TokenEndpoint;

        if (StringUtils.isBlank(serviceManagementURL)) {
            this.serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
        } else {
            this.serviceManagementURL = serviceManagementURL;
        }

        final String home = Jenkins.getInstance().root.getPath();

        LOGGER.log(Level.INFO, "Cache home \"{0}\"", home);

        final StringBuilder builder = new StringBuilder(home);
        builder.append(File.separatorChar).append("azuretoken.txt");
        this.path = builder.toString();

        LOGGER.log(Level.INFO, "Cache file path \"{0}\"", path);
    }

    public AccessToken get() throws AzureCloudException {
        LOGGER.log(Level.INFO, "Get token from cache");
        synchronized (tsafe) {
            AccessToken token = readTokenFile();
            if (token == null || token.isExpiring()) {
                LOGGER.log(Level.INFO, "Token is no longer valid ({0})",
                        token == null ? null : token.getExpirationDate());
                clear();
                token = getNewToken();
            }
            return token;
        }
    }

    public final void clear() {
        LOGGER.log(Level.INFO, "Remove cache file {0}", path);
        FileUtils.deleteQuietly(new File(path));
    }

    private AccessToken readTokenFile() {
        LOGGER.log(Level.INFO, "Read token from file {0}", path);
        FileInputStream is = null;
        ObjectInputStream objectIS = null;

        try {
            final File fileCache = new File(path);
            if (fileCache.exists()) {
                is = new FileInputStream(fileCache);
                objectIS = new ObjectInputStream(is);
                return AccessToken.class.cast(objectIS.readObject());
            } else {
                LOGGER.log(Level.INFO, "File {0} does not exist", path);
            }
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Cache file not found", e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error reading serialized object", e);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Error deserializing object", e);
        } finally {
            IOUtils.closeQuietly(is);
            IOUtils.closeQuietly(objectIS);
        }

        return null;
    }

    private boolean writeTokenFile(final AccessToken token) {
        LOGGER.log(Level.INFO, "Write token into file {0}", path);

        FileOutputStream fout = null;
        ObjectOutputStream oos = null;

        boolean res = false;

        try {
            fout = new FileOutputStream(path, false);
            oos = new ObjectOutputStream(fout);
            oos.writeObject(token);
            res = true;
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Cache file not found", e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error serializing object", e);
        } finally {
            IOUtils.closeQuietly(fout);
            IOUtils.closeQuietly(oos);
        }

        return res;
    }

    private AccessToken getNewToken() throws AzureCloudException {
        LOGGER.log(Level.INFO, "Retrieve new access token");
        // reset configuration instance: renew token
        //Configuration.setInstance(null);

        final ExecutorService service = Executors.newFixedThreadPool(1);

        AuthenticationResult authres = null;

        try {
            LOGGER.log(Level.INFO, "Aquiring access token: \n\t{0}\n\t{1}\n\t{2}",
                    new Object[]{oauth2TokenEndpoint, serviceManagementURL, clientId});

            final ClientCredential credential = new ClientCredential(clientId, clientSecret);

            final Future<AuthenticationResult> future = new AuthenticationContext(oauth2TokenEndpoint, false, service).
                    acquireToken(serviceManagementURL, credential, null);

            authres = future.get();
        } catch (MalformedURLException e) {
            throw new AzureCloudException("Authentication error", e);
        } catch (InterruptedException e) {
            throw new AzureCloudException("Authentication interrupted", e);
        } catch (ExecutionException e) {
            throw new AzureCloudException("Authentication execution failed", e);
        } finally {
            service.shutdown();
        }

        if (authres == null) {
            throw new AzureCloudException("Authentication result was null");
        }

        LOGGER.log(Level.INFO,
                "Authentication result:\n\taccess token: {0}\n\tExpires On: {1}",
                new Object[]{authres.getAccessToken(), new Date(authres.getExpiresOn())});

        final AccessToken token = new AccessToken(subscriptionId, serviceManagementURL, authres);
        writeTokenFile(token);
        return token;
    }
}
