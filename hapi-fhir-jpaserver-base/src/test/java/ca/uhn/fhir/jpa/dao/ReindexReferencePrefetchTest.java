package ca.uhn.fhir.jpa.dao;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.model.PersistentIdToForcedIdMap;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.IdAndPartitionId;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.searchparam.extractor.ResourceIndexedSearchParams;
import ca.uhn.fhir.jpa.searchparam.extractor.ReindexBatchPrefetch;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Created by Codex
class ReindexReferencePrefetchTest {

	private static final String PREFETCH_KEY = ReindexBatchPrefetch.EXISTING_REFERENCE_IDS;
	private final BaseHapiFhirDao<?> myDao = mock(BaseHapiFhirDao.class, CALLS_REAL_METHODS);
	@SuppressWarnings("unchecked")
	private final IIdHelperService<JpaPid> myIdHelper = mock(IIdHelperService.class);
	private final TransactionDetails myTransaction = new TransactionDetails();
	private final BaseHapiFhirSystemDao<?, ?> mySystemDao = mock(BaseHapiFhirSystemDao.class, CALLS_REAL_METHODS);

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(myDao, "myContext", FhirContext.forR4Cached());
		ReflectionTestUtils.setField(myDao, "myIdHelperService", myIdHelper);
		ReflectionTestUtils.setField(mySystemDao, "myIdHelperService", myIdHelper);
		when(myIdHelper.translatePidsToForcedIds(anySet())).thenReturn(new PersistentIdToForcedIdMap<>(Map.of()));
	}

	@Test
	void usesBatchPrefetchedIdentityWithoutAnotherQuery() {
		JpaPid pid = new JpaPid(1001, 11L);
		myTransaction.putUserData(PREFETCH_KEY, new HashMap<>(Map.of(key(pid), "Patient/synthetic")));

		resolve(pid, pid);

		assertThat(myTransaction.getResolvedResourceId(new IdType("Patient/synthetic"))).isEqualTo(pid);
		verifyNoInteractions(myIdHelper);
	}

	@Test
	void activatesOnlyThisResourcesReferencesAndKeepsPartitionInTheLookupKey() {
		JpaPid first = new JpaPid(1001, 11L);
		JpaPid second = new JpaPid(1002, 11L);
		myTransaction.putUserData(PREFETCH_KEY, new HashMap<>(Map.of(
				key(first), "Patient/first", key(second), "Patient/second")));

		resolve(second);

		assertThat(myTransaction.getResolvedResourceId(new IdType("Patient/second"))).isEqualTo(second);
		assertThat(myTransaction.getResolvedResourceId(new IdType("Patient/first"))).isNull();
		verifyNoInteractions(myIdHelper);
	}

	@Test
	void missingPrefetchEntryStillUsesOrdinaryIdentityResolution() {
		JpaPid pid = new JpaPid(1001, 12L);
		myTransaction.putUserData(PREFETCH_KEY, new HashMap<IdAndPartitionId, String>());
		when(myIdHelper.translatePidsToForcedIds(Set.of(pid)))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(pid, Optional.of("Patient/new-target"))));

		resolve(pid);

		assertThat(myTransaction.getResolvedResourceId(new IdType("Patient/new-target"))).isEqualTo(pid);
		verify(myIdHelper).translatePidsToForcedIds(Set.of(pid));
	}

	@Test
	void prefetchDeduplicatesTheBatchWithoutActivatingReferencesOrCachingMissingTargets() {
		JpaPid first = new JpaPid(1001, 11L);
		JpaPid missing = new JpaPid(1001, 12L);
		when(myIdHelper.translatePidsToForcedIds(Set.of(first, missing)))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(
						first, Optional.of("Patient/synthetic"), missing, Optional.empty())));

		mySystemDao.prefetchExistingReferenceIds(List.of(entity(first, missing), entity(first)), myTransaction);

		Map<IdAndPartitionId, String> prefetched = myTransaction.getUserData(PREFETCH_KEY);
		assertThat(prefetched).containsExactlyEntriesOf(Map.of(key(first), "Patient/synthetic"));
		assertThat(myTransaction.getResolvedResourceId(new IdType("Patient/synthetic"))).isNull();
		verify(myIdHelper).translatePidsToForcedIds(Set.of(first, missing));
		reset(myIdHelper);
		resolve(first);
		verifyNoInteractions(myIdHelper);
	}

	@Test
	void rollbackClearsPrefetchedIdentitiesBeforeRetry() {
		JpaPid pid = new JpaPid(1001, 11L);
		when(myIdHelper.translatePidsToForcedIds(Set.of(pid)))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(pid, Optional.of("Patient/synthetic"))))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(pid, Optional.empty())));
		mySystemDao.prefetchExistingReferenceIds(List.of(entity(pid)), myTransaction);

		myTransaction.getRollbackUndoActions().forEach(Runnable::run);
		mySystemDao.prefetchExistingReferenceIds(List.of(entity(pid)), myTransaction);

		Map<IdAndPartitionId, String> prefetched = myTransaction.getUserData(PREFETCH_KEY);
		assertThat(prefetched).isEmpty();
		verify(myIdHelper, times(2)).translatePidsToForcedIds(Set.of(pid));
	}

	@Test
	void repeatedPrefetchIsIdempotentAndDoesNotLeakIntoAnotherTransaction() {
		JpaPid pid = new JpaPid(1001, 11L);
		when(myIdHelper.translatePidsToForcedIds(Set.of(pid)))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(pid, Optional.of("Patient/synthetic"))));
		mySystemDao.prefetchExistingReferenceIds(List.of(entity(pid)), myTransaction);
		mySystemDao.prefetchExistingReferenceIds(List.of(entity(pid)), myTransaction);

		TransactionDetails next = new TransactionDetails();
		assertThat((Object) next.getUserData(PREFETCH_KEY)).isNull();
		mySystemDao.prefetchExistingReferenceIds(List.of(entity(pid)), next);

		verify(myIdHelper, times(2)).translatePidsToForcedIds(Set.of(pid));
		assertThat(myTransaction.getRollbackUndoActions()).hasSize(1);
	}

	@Test
	void resourcesWithoutLocalLinksDoNotCauseIdentityQueries() {
		ResourceTable noLinks = mock(ResourceTable.class);
		mySystemDao.prefetchExistingReferenceIds(List.of(noLinks, entity((JpaPid) null)), myTransaction);

		verifyNoInteractions(myIdHelper);
	}

	@Test
	void alreadyResolvedReferencesDoNotNeedAnotherQuery() {
		JpaPid pid = new JpaPid(1001, 11L);
		myTransaction.addResolvedResourceId(new IdType("Patient/synthetic"), pid);

		resolve(pid);

		verifyNoInteractions(myIdHelper);
	}

	private void resolve(JpaPid... thePids) {
		ResourceIndexedSearchParams params = mock(ResourceIndexedSearchParams.class);
		List<ResourceLink> links = links(thePids);
		when(params.getResourceLinks()).thenReturn(links);
		ReflectionTestUtils.invokeMethod(myDao, "preResolveExistingReferences", myTransaction, params);
	}

	private ResourceTable entity(JpaPid... thePids) {
		ResourceTable entity = mock(ResourceTable.class);
		when(entity.isHasLinks()).thenReturn(true);
		List<ResourceLink> links = links(thePids);
		when(entity.getResourceLinks()).thenReturn(links);
		return entity;
	}

	private List<ResourceLink> links(JpaPid... thePids) {
		return Arrays.stream(thePids).map(pid -> {
			ResourceLink link = mock(ResourceLink.class);
			when(link.getTargetResourcePk()).thenReturn(pid);
			return link;
		}).toList();
	}

	private static IdAndPartitionId key(JpaPid thePid) {
		return new IdAndPartitionId(thePid.getId(), thePid.getPartitionId());
	}
}
