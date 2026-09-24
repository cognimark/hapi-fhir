/*
 * #%L
 * HAPI FHIR - Core Library
 * %%
 * Copyright (C) 2014 - 2026 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.parser;

import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.io.Serializable;
import java.util.Objects;

/** Lexical source attached only after the normal XHTML parser has succeeded. */
// Created by Codex
final class JsonXhtmlSource {
	private static final String KEY = JsonXhtmlSource.class.getName();

	private JsonXhtmlSource() {}

	static void capture(IPrimitiveType<?> theValue, String theSource) {
		String encoded = theValue.getValueAsString();
		// Plain text is historically wrapped in a div by the parser. Do not undo
		// that behavior or retain an empty narrative discarded by the model.
		if (encoded != null && theSource.stripLeading().startsWith("<") && !theSource.equals(encoded)) {
			theValue.setUserData(KEY, new Source(theSource, encoded));
		}
	}

	static String forEncoding(IPrimitiveType<?> theValue, String theEncoded) {
		Object data = theValue.getUserData(KEY);
		if (data instanceof Source source && Objects.equals(source.encoded(), theEncoded)) {
			return source.original();
		}
		// A changed model must never resurrect a prior narrative. The marker is
		// per value, not a global cache and not an alternate clinical data store.
		if (data != null) {
			theValue.setUserData(KEY, null);
		}
		return theEncoded;
	}

	private record Source(String original, String encoded) implements Serializable {}
}
