// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventservice;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceClient;
import com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceGrpcClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import io.grpc.ManagedChannel;
import io.grpc.ClientInterceptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.Set;

/**
 * Bazel's BES module.
 */
public class BazelBuildEventServiceModule
    extends BuildEventServiceModule<BuildEventServiceOptions> {

  @AutoValue
  abstract static class BackendConfig {
    abstract String besBackend();

    abstract AuthAndTLSOptions authAndTLSOptions();
  }

  private BuildEventServiceClient client;
  private BackendConfig config;

  @Override
  protected Class<BuildEventServiceOptions> optionsClass() {
    return BuildEventServiceOptions.class;
  }

  @Override
  protected BuildEventServiceClient getBesClient(
      BuildEventServiceOptions besOptions,
      AuthAndTLSOptions authAndTLSOptions, 
      RemoteOptions remoteOptions) throws IOException {
    BackendConfig newConfig =
        new AutoValue_BazelBuildEventServiceModule_BackendConfig(
            besOptions.besBackend, authAndTLSOptions);
    if (client == null || !Objects.equals(config, newConfig)) {
      clearBesClient();
      config = newConfig;
      client =
          new BuildEventServiceGrpcClient(
              newGrpcChannel(besOptions, authAndTLSOptions, remoteOptions),
              GoogleAuthUtils.newCallCredentials(authAndTLSOptions));
    }
    return client;
  }

  // newGrpcChannel is only defined so it can be overridden in tests to not use a real network link.
  @VisibleForTesting
  protected ManagedChannel newGrpcChannel(
      BuildEventServiceOptions besOptions, 
      AuthAndTLSOptions authAndTLSOptions, 
      RemoteOptions remoteOptions) throws IOException {
    List<ClientInterceptor> interceptors = new ArrayList<>();
    if (remoteOptions != null) {
      interceptors.add(TracingMetadataUtils.newBESHeadersInterceptor(remoteOptions));
    }
    return GoogleAuthUtils.newChannel(
        besOptions.besBackend,
        besOptions.besProxy, 
        authAndTLSOptions,
        interceptors);
  }

  @Override
  protected void clearBesClient() {
    if (client != null) {
      client.shutdown();
    }
    this.client = null;
    this.config = null;
  }

  private static final ImmutableSet<String> WHITELISTED_COMMANDS =
      ImmutableSet.of(
          "fetch",
          "build",
          "test",
          "run",
          "query",
          "aquery",
          "cquery",
          "coverage",
          "mobile-install");

  @Override
  protected Set<String> whitelistedCommands(BuildEventServiceOptions besOptions) {
    return WHITELISTED_COMMANDS;
  }

  @Override
  protected String getInvocationIdPrefix() {
    if (Strings.isNullOrEmpty(besOptions.besResultsUrl)) {
      return "";
    }
    return besOptions.besResultsUrl.endsWith("/")
        ? besOptions.besResultsUrl
        : besOptions.besResultsUrl + "/";
  }

  @Override
  protected String getBuildRequestIdPrefix() {
    return "";
  }
}
