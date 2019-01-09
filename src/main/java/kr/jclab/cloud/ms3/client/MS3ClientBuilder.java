/*
 * Copyright 2018 JC-Lab. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kr.jclab.cloud.ms3.client;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

public class MS3ClientBuilder {
    private static String m_defaultServerUrl;

    private String serverUrl = m_defaultServerUrl;

    private HttpClientBuilder m_httpClientBuilder = HttpClientBuilder.create();

    public static void init(String defaultServerUrl) {
        m_defaultServerUrl = defaultServerUrl;
    }

    public static MS3ClientBuilder standard() {
        return new MS3ClientBuilder();
    }

    public static MS3Client defaultClient() {
        return standard().build();
    }

    public HttpClientBuilder getHttpClientBuilder() {
        if(m_httpClientBuilder == null) {
            m_httpClientBuilder = HttpClientBuilder.create();
        }
        return m_httpClientBuilder;
    }

    public MS3ClientBuilder httpClientBuilder(HttpClientBuilder httpClientBuilder) {
        m_httpClientBuilder = httpClientBuilder;
        return this;
    }

    public MS3ClientBuilder serverUrl(String serverUrl) {
        this.serverUrl = serverUrl;
        return this;
    }

    public MS3Client build() {
        MS3Client ms3Client = null;
        HttpClient httpClient = getHttpClientBuilder().build();
        ms3Client = new MS3Client(serverUrl, httpClient);
        return ms3Client;
    }
}
