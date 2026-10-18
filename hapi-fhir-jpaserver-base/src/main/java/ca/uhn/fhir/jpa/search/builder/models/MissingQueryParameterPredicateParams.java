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
package ca.uhn.fhir.jpa.search.builder.models;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.search.builder.predicate.ResourceTablePredicateBuilder;
import com.healthmarketscience.sqlbuilder.Condition;
import com.healthmarketscience.sqlbuilder.dbspec.basic.DbColumn;

/** The resource at the current point in a search chain, and its correlated columns. */
public class MissingQueryParameterPredicateParams {
	private final String myResourceType;
	private final DbColumn[] mySourceJoinColumns;
	private final boolean myIsMissing;
	private final String myParamName;
	private final RequestPartitionId myRequestPartitionId;

	public MissingQueryParameterPredicateParams(MissingParameterQueryParams theParams) {
		myResourceType = theParams.getResourceType();
		ResourceTablePredicateBuilder resource = theParams.getSqlBuilder().getOrCreateResourceTablePredicateBuilder();
		mySourceJoinColumns =
				theParams.getSourceJoinColumn() != null ? theParams.getSourceJoinColumn() : resource.getJoinColumns();
		// NOT EXISTS cannot constrain the outer resource through an index row,
		// including when the missing parameter is reached through a reference.
		Condition partition = resource.createPartitionIdPredicate(theParams.getRequestPartitionId());
		if (partition != null) {
			theParams.getSqlBuilder().addPredicate(partition);
		}
		myIsMissing = theParams.isMissing();
		myParamName = theParams.getParamName();
		myRequestPartitionId = theParams.getRequestPartitionId();
	}

	public String getResourceType() {
		return myResourceType;
	}

	public DbColumn[] getSourceJoinColumns() {
		return mySourceJoinColumns;
	}

	public boolean isMissing() {
		return myIsMissing;
	}

	public String getParamName() {
		return myParamName;
	}

	public RequestPartitionId getRequestPartitionId() {
		return myRequestPartitionId;
	}
}
