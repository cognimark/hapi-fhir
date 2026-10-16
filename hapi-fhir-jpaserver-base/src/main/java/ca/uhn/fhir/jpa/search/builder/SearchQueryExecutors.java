/*-
 * #%L
 * HAPI FHIR JPA Server
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
package ca.uhn.fhir.jpa.search.builder;

import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.search.builder.models.ResolvedSearchQueryExecutor;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang3.Validate;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class SearchQueryExecutors {

	/** SQL determines membership; the native full-text engine determines order. */
	public static ISearchQueryExecutor inCandidateOrder(List<JpaPid> theCandidates, ISearchQueryExecutor theQuery) {
		return new ISearchQueryExecutor() {
			Iterator<JpaPid> myMatches;
			boolean myClosed;

			@Override
			public boolean hasNext() {
				if (myClosed) {
					return false;
				}
				if (myMatches == null) {
					HashMap<Long, JpaPid> accepted = new HashMap<>();
					while (theQuery.hasNext()) {
						JpaPid match = theQuery.next();
						accepted.put(match.getId(), match);
					}
					myMatches = theCandidates.stream()
							.map(pid -> accepted.remove(pid.getId()))
							.filter(Objects::nonNull)
							.iterator();
				}
				return myMatches.hasNext();
			}

			@Override
			public JpaPid next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}
				return myMatches.next();
			}

			@Override
			public void close() {
				if (!myClosed) {
					myClosed = true;
					theQuery.close();
				}
			}
		};
	}

	/** Apply pagination once, after each ordered candidate chunk has passed SQL filtering. */
	public static ISearchQueryExecutor window(
			List<ISearchQueryExecutor> theQueries, Integer theOffset, Integer theLimit) {
		long offset = theOffset == null ? 0 : theOffset;
		long limit = theLimit == null ? Long.MAX_VALUE : theLimit;
		Validate.isTrue(offset >= 0 && limit >= 0, "offset and limit must be non-negative");
		return new ISearchQueryExecutor() {
			int myPosition;
			long mySkipped;
			long myReturned;
			boolean myClosed;

			@Override
			public boolean hasNext() {
				if (myClosed || myReturned >= limit) {
					close();
					return false;
				}
				while (myPosition < theQueries.size()) {
					ISearchQueryExecutor query = theQueries.get(myPosition);
					if (!query.hasNext()) {
						query.close();
						myPosition++;
					} else if (mySkipped < offset) {
						query.next();
						mySkipped++;
					} else {
						return true;
					}
				}
				close();
				return false;
			}

			@Override
			public JpaPid next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}
				myReturned++;
				return theQueries.get(myPosition).next();
			}

			@Override
			public void close() {
				if (!myClosed) {
					myClosed = true;
					while (myPosition < theQueries.size()) {
						theQueries.get(myPosition++).close();
					}
				}
			}
		};
	}

	public static ISearchQueryExecutor limited(ISearchQueryExecutor theExecutor, long theLimit) {
		Validate.isTrue(theLimit >= 0, "limit must be non-negative");

		return new ISearchQueryExecutor() {
			long myCount = 0;

			@Override
			public void close() {
				theExecutor.close();
			}

			@Override
			public boolean hasNext() {
				return theExecutor.hasNext() && myCount < theLimit;
			}

			@Override
			public JpaPid next() {
				myCount += 1;
				return theExecutor.next();
			}
		};
	}

	@Nonnull
	public static ISearchQueryExecutor from(List<JpaPid> rawPids) {
		return new ResolvedSearchQueryExecutor(rawPids);
	}
}
