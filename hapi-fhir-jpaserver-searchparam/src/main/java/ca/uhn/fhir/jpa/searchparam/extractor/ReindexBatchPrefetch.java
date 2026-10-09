/*-
 * #%L
 * HAPI FHIR JPA - Search Parameters
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
package ca.uhn.fhir.jpa.searchparam.extractor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.api.svc.ResolveIdentityMode;
import ca.uhn.fhir.jpa.model.cross.IResourceLookup;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.IdAndPartitionId;
import ca.uhn.fhir.jpa.model.entity.ResourceHistoryTable;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.ResourceReferenceInfo;
import jakarta.annotation.Nullable;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Bounded, transaction-local prefetch for a native reindex chunk. Positive target
 * lookups are not activated in TransactionDetails until ordinary link validation.
 * Missing targets still go through the native placeholder/deleted-target path.
 */
// Created by Codex
public final class ReindexBatchPrefetch {
	/** Existing link identities share the same chunk/transaction lifetime as incoming target lookups. */
	public static final String EXISTING_REFERENCE_IDS = ReindexBatchPrefetch.class.getName() + ".existingReferenceIds";

	private static final String USER_DATA_KEY = ReindexBatchPrefetch.class.getName();
	private final Map<IdAndPartitionId, ParsedResource> myResources = new HashMap<>();
	private final Map<TargetKey, IdAndPartitionId> myTargetIds = new HashMap<>();
	private final Map<IdAndPartitionId, IResourceLookup<JpaPid>> myTargets = new HashMap<>();

	private ReindexBatchPrefetch() {}

	/** Parse once and resolve distinct relative reference targets before this chunk starts writing indexes. */
	public static void prefetch(
			TransactionDetails theTransaction,
			Collection<ResourceTable> theEntities,
			Function<ResourceTable, IBaseResource> theParser,
			FhirContext theContext,
			IIdHelperService<JpaPid> theIdHelper,
			RequestPartitionId theDefaultPartition) {
		ReindexBatchPrefetch state = theTransaction.getOrCreateUserData(USER_DATA_KEY, () -> {
			ReindexBatchPrefetch created = new ReindexBatchPrefetch();
			theTransaction.addRollbackUndoAction(created::clear);
			return created;
		});
		Map<RequestPartitionId, Map<String, IIdType>> targets = new LinkedHashMap<>();
		FhirTerser terser = theContext.newTerser();
		for (ResourceTable entity : theEntities) {
			IdAndPartitionId key = key(entity.getPersistentId());
			if (state.myResources.containsKey(key)) {
				continue;
			}
			IBaseResource resource;
			try {
				resource = theParser.apply(entity);
			} catch (RuntimeException e) {
				// Re-throw at the usual per-resource boundary, where reindex records its warning.
				state.myResources.put(
						key, new ParsedResource(entity.getVersion(), entity.getCurrentVersionEntity(), null, e));
				continue;
			}
			state.myResources.put(
					key, new ParsedResource(entity.getVersion(), entity.getCurrentVersionEntity(), resource, null));
			if (resource == null || entity.getDeleted() != null) {
				continue;
			}
			RequestPartitionId partition = entity.getPartitionId() == null
					? theDefaultPartition
					: entity.getPartitionId().toPartitionId();
			Set<String> existingTargets = existingTargets(theTransaction, entity);
			for (ResourceReferenceInfo referenceInfo : terser.getAllResourceReferences(resource)) {
				IBaseReference reference = referenceInfo.getResourceReference();
				IIdType id = reference.getReferenceElement();
				if (id.isEmpty() && reference.getResource() != null) {
					id = reference.getResource().getIdElement();
				}
				if (!id.hasResourceType()
						|| !id.hasIdPart()
						|| id.isLocal()
						|| id.hasBaseUrl()
						|| id.getValue().contains("?")
						|| id.getValue().contains(":")) {
					continue;
				}
				IIdType normalized = id.toUnqualifiedVersionless();
				// These identities are activated by native preResolveExistingReferences
				// for this same source resource; querying them again has no value.
				if (existingTargets.contains(normalized.getValue())) {
					continue;
				}
				targets.computeIfAbsent(partition, ignored -> new LinkedHashMap<>())
						.putIfAbsent(normalized.getValue(), normalized);
			}
		}
		targets.forEach((partition, ids) -> theIdHelper
				.resolveResourceIdentities(
						partition,
						ids.values(),
						ResolveIdentityMode.excludeDeleted().noCacheUnlessDeletesDisabled())
				.forEach((id, lookup) -> {
					if (lookup.getDeleted() == null) {
						IdAndPartitionId key = key(lookup.getPersistentId());
						state.myTargetIds.put(new TargetKey(partition, id.getResourceType(), id.getIdPart()), key);
						state.myTargets.put(key, lookup);
					}
				}));
	}

	/** Consume a prefetched body once, unless a version correction or a write invalidated it. */
	public static IBaseResource takeResource(
			TransactionDetails theTransaction,
			ResourceTable theEntity,
			Function<ResourceTable, IBaseResource> theParser) {
		ReindexBatchPrefetch state = get(theTransaction);
		ParsedResource parsed = state == null ? null : state.myResources.remove(key(theEntity.getPersistentId()));
		if (parsed == null
				|| parsed.version() != theEntity.getVersion()
				|| parsed.history() != theEntity.getCurrentVersionEntity()) {
			return theParser.apply(theEntity);
		}
		if (parsed.failure() != null) {
			throw parsed.failure();
		}
		return parsed.resource();
	}

	/** Read a positive lookup only for the exact request partition; the caller must still validate it. */
	@Nullable
	public static IResourceLookup<?> findTarget(
			@Nullable TransactionDetails theTransaction, RequestPartitionId thePartition, IIdType theId) {
		ReindexBatchPrefetch state = get(theTransaction);
		if (state == null) {
			return null;
		}
		IdAndPartitionId key =
				state.myTargetIds.get(new TargetKey(thePartition, theId.getResourceType(), theId.getIdPart()));
		IResourceLookup<?> lookup = state.myTargets.get(key);
		return lookup == null || lookup.getDeleted() != null ? null : lookup;
	}

	/** Invalidate both source and target state before an ordinary update or delete changes an entity. */
	public static void invalidate(@Nullable TransactionDetails theTransaction, ResourceTable theEntity) {
		ReindexBatchPrefetch state = get(theTransaction);
		if (state != null && theEntity.getPersistentId() != null) {
			IdAndPartitionId key = key(theEntity.getPersistentId());
			state.myResources.remove(key);
			state.myTargets.remove(key);
		}
	}

	@Nullable
	private static ReindexBatchPrefetch get(@Nullable TransactionDetails theTransaction) {
		return theTransaction == null ? null : theTransaction.getUserData(USER_DATA_KEY);
	}

	private static IdAndPartitionId key(JpaPid thePid) {
		return new IdAndPartitionId(thePid.getId(), thePid.getPartitionId());
	}

	private static Set<String> existingTargets(TransactionDetails theTransaction, ResourceTable theEntity) {
		Map<IdAndPartitionId, String> identities = theTransaction.getUserData(EXISTING_REFERENCE_IDS);
		if (identities == null || !theEntity.isHasLinks()) {
			return Set.of();
		}
		Set<String> result = new HashSet<>();
		for (ResourceLink link : theEntity.getResourceLinks()) {
			JpaPid pid = link.getTargetResourcePk();
			if (pid != null) {
				String id = identities.get(key(pid));
				if (id != null) {
					result.add(id);
				}
			}
		}
		return result;
	}

	private void clear() {
		myResources.clear();
		myTargetIds.clear();
		myTargets.clear();
	}

	private record TargetKey(RequestPartitionId partition, String type, String id) {}

	private record ParsedResource(
			long version, ResourceHistoryTable history, IBaseResource resource, RuntimeException failure) {}
}
