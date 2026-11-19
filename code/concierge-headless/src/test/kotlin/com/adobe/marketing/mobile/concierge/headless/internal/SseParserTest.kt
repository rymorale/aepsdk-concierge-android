/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.concierge.headless.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    private fun parse(vararg lines: String): List<String> {
        val parser = SseParser()
        return lines.mapNotNull(parser::feed) + listOfNotNull(parser.finish())
    }

    @Test
    fun `dispatches data on a blank line`() {
        assertEquals(listOf("a", "b"), parse("data: a", "", "data: b", ""))
    }

    @Test
    fun `joins multi-line data with newlines`() {
        assertEquals(listOf("one\ntwo"), parse("data: one", "data: two", ""))
    }

    @Test
    fun `ignores comments and other fields`() {
        assertEquals(listOf("x"), parse(": keep-alive", "event: message", "id: 7", "retry: 10", "data: x", ""))
    }

    @Test
    fun `removes only one leading space and keeps a value without a colon empty`() {
        assertEquals(listOf("  padded\n"), parse("data:   padded", "data", ""))
    }

    @Test
    fun `strips a byte order mark from the first line`() {
        assertEquals(listOf("x"), parse("\uFEFFdata: x", ""))
    }

    @Test
    fun `flushes an unterminated event at the end of the stream`() {
        assertEquals(listOf("tail"), parse("data: tail"))
    }

    @Test
    fun `blank lines without data dispatch nothing`() {
        val parser = SseParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed("event: ping"))
        assertNull(parser.feed(""))
        assertNull(parser.finish())
    }
}
