/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.platon.pulsar.crawl.protocol;

@SuppressWarnings("serial")
public class ProtocolNotFound extends ProtocolException {
    private String url;

    public ProtocolNotFound(String url) {
        this(url, "protocol not found for url=" + url);
    }

    public ProtocolNotFound(String url, String message) {
        super(message);
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
