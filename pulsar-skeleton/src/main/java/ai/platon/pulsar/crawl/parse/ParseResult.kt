/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.crawl.parse

import ai.platon.pulsar.persist.HypeLink
import ai.platon.pulsar.persist.ParseStatus
import ai.platon.pulsar.persist.metadata.ParseStatusCodes
import org.jsoup.nodes.Document
import java.util.*

class ParseResult : ParseStatus {
    val hypeLinks = ArrayList<HypeLink>()
    var document: Document? = null
    var parser: Parser? = null

    constructor() : super(NOTPARSED, SUCCESS_OK) {}
    constructor(majorCode: Short, minorCode: Int) : super(majorCode, minorCode) {}
    constructor(majorCode: Short, minorCode: Int, message: String?) : super(majorCode, minorCode, message) {}

    companion object {
        fun failed(minorCode: Int, message: String?): ParseResult {
            return ParseResult(ParseStatusCodes.FAILED, minorCode, message)
        }

        @JvmStatic
        fun failed(e: Throwable): ParseResult {
            return ParseResult(ParseStatusCodes.FAILED, ParseStatusCodes.FAILED_EXCEPTION, e.message)
        }
    }
}
