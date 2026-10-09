package ca.uhn.fhir.jpa.searchparam.extractor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.PartitionablePartitionId;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.entity.IdAndPartitionId;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Created by Codex
class ReindexBatchPrefetchTest {

	private final FhirContext myContext = FhirContext.forR4Cached();
	private final TransactionDetails myTransaction = new TransactionDetails();
	private final RequestPartitionId myPartition = RequestPartitionId.fromPartitionId(1001);
	@SuppressWarnings("unchecked")
	private final IIdHelperService<JpaPid> myIdHelper = mock(IIdHelperService.class);

	@Test
	void alreadyStoredReferencesDoNotNeedAnotherBatchIdentityQuery() {
		ResourceTable source = entity(1001, 1L, "Observation", "source");
		ResourceLink link = mock(ResourceLink.class);
		when(link.getTargetResourcePk()).thenReturn(new JpaPid(1001, 2L));
		source.setHasLinks(true);
		source.setResourceLinks(List.of(link));
		myTransaction.putUserData(ReindexBatchPrefetch.EXISTING_REFERENCE_IDS,
				Map.of(new IdAndPartitionId(2L, 1001), "Patient/target"));

		prefetch(List.of(source), ignored -> new Observation().setSubject(new Reference("Patient/target")));

		verifyNoInteractions(myIdHelper);
	}

	@Test
	void anotherResourcesStoredReferenceDoesNotHideAnIncomingTarget() {
		ResourceTable source = entity(1001, 1L, "Observation", "source");
		ResourceTable target = entity(1001, 2L, "Patient", "target");
		myTransaction.putUserData(ReindexBatchPrefetch.EXISTING_REFERENCE_IDS,
				Map.of(new IdAndPartitionId(2L, 1001), "Patient/target"));
		when(myIdHelper.resolveResourceIdentities(eq(myPartition), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/target"), target));

		prefetch(List.of(source), ignored -> new Observation().setSubject(new Reference("Patient/target")));

		verify(myIdHelper).resolveResourceIdentities(eq(myPartition), any(), any());
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/target"))).isSameAs(target);
	}

	@Test
	void batchesDistinctIncomingTargetsAndReusesParsedBodiesWithoutActivatingThem() {
		ResourceTable first = entity(1001, 1L, "Observation", "one");
		ResourceTable second = entity(1001, 2L, "Observation", "two");
		ResourceTable target = entity(1001, 3L, "Patient", "target");
		Observation body = new Observation().setSubject(new Reference("Patient/target"));
		body.addPerformer(new Reference("Patient/target/_history/2"));
		AtomicInteger parses = new AtomicInteger();
		Function<ResourceTable, IBaseResource> parser = ignored -> { parses.incrementAndGet(); return body; };
		when(myIdHelper.resolveResourceIdentities(eq(myPartition), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/target"), target));

		prefetch(List.of(first, second), parser);

		ArgumentCaptor<Collection> ids = ArgumentCaptor.forClass(Collection.class);
		verify(myIdHelper).resolveResourceIdentities(eq(myPartition), ids.capture(), any());
		assertThat(ids.getValue()).hasSize(1);
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/target")))
				.isSameAs(target);
		assertThat(myTransaction.getResolvedResourceId(new IdType("Patient/target"))).isNull();
		assertThat(ReindexBatchPrefetch.takeResource(myTransaction, first, parser)).isSameAs(body);
		assertThat(ReindexBatchPrefetch.takeResource(myTransaction, second, parser)).isSameAs(body);
		assertThat(parses).hasValue(2);
		ReindexBatchPrefetch.takeResource(myTransaction, first, parser);
		assertThat(parses).hasValue(3);
	}

	@Test
	void partitionsWithTheSameFhirIdStaySeparateAndNeverBecomeAllPartitionLookups() {
		ResourceTable first = entity(1001, 1L, "Observation", "same");
		ResourceTable second = entity(1002, 2L, "Observation", "same");
		ResourceTable target1 = entity(1001, 3L, "Patient", "same");
		ResourceTable target2 = entity(1002, 4L, "Patient", "same");
		RequestPartitionId partition2 = RequestPartitionId.fromPartitionId(1002);
		when(myIdHelper.resolveResourceIdentities(eq(myPartition), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/same"), target1));
		when(myIdHelper.resolveResourceIdentities(eq(partition2), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/same"), target2));

		prefetch(List.of(first, second), ignored -> new Observation().setSubject(new Reference("Patient/same")));

		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/same"))).isSameAs(target1);
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, partition2, new IdType("Patient/same"))).isSameAs(target2);
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, RequestPartitionId.allPartitions(), new IdType("Patient/same"))).isNull();
	}

	@Test
	void missingAndDeletedTargetsAreNotCachedAndRollbackClearsAllPrefetchedState() {
		ResourceTable entity = entity(1001, 1L, "Observation", "one");
		ResourceTable target = entity(1001, 3L, "Patient", "target");
		ResourceTable deleted = entity(1001, 4L, "Patient", "deleted");
		deleted.setDeleted(new Date());
		when(myIdHelper.resolveResourceIdentities(eq(myPartition), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/target"), target, new IdType("Patient/deleted"), deleted));
		AtomicInteger parses = new AtomicInteger();
		Function<ResourceTable, IBaseResource> parser = ignored -> {
			parses.incrementAndGet();
			return new Observation().setSubject(new Reference("Patient/target"));
		};
		prefetch(List.of(entity), parser);
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/missing"))).isNull();
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/deleted"))).isNull();

		myTransaction.getRollbackUndoActions().forEach(Runnable::run);

		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/target"))).isNull();
		ReindexBatchPrefetch.takeResource(myTransaction, entity, parser);
		assertThat(parses).hasValue(2);
		assertThat(ReindexBatchPrefetch.findTarget(new TransactionDetails(), myPartition, new IdType("Patient/target"))).isNull();
	}

	@Test
	void changedVersionsAndExplicitWritesInvalidateParsedBodiesAndTargets() {
		ResourceTable entity = entity(1001, 1L, "Observation", "one");
		ResourceTable target = entity(1001, 3L, "Patient", "target");
		when(myIdHelper.resolveResourceIdentities(eq(myPartition), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/target"), target));
		AtomicInteger parses = new AtomicInteger();
		Function<ResourceTable, IBaseResource> parser = ignored -> {
			parses.incrementAndGet();
			return new Observation().setSubject(new Reference("Patient/target"));
		};
		prefetch(List.of(entity), parser);
		entity.setVersionForUnitTest(2);
		ReindexBatchPrefetch.takeResource(myTransaction, entity, parser);
		assertThat(parses).hasValue(2);

		ReindexBatchPrefetch.invalidate(myTransaction, target);
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/target"))).isNull();
		prefetch(List.of(entity), parser);
		ReindexBatchPrefetch.invalidate(myTransaction, entity);
		ReindexBatchPrefetch.takeResource(myTransaction, entity, parser);
		assertThat(parses).hasValue(4);
	}

	@Test
	void defersParseFailuresToTheIndividualResourceAndDoesNotParseItTwice() {
		ResourceTable bad = entity(1001, 1L, "Observation", "bad");
		ResourceTable good = entity(1001, 2L, "Observation", "good");
		RuntimeException failure = new IllegalArgumentException("synthetic invalid body");
		AtomicInteger parses = new AtomicInteger();
		Function<ResourceTable, IBaseResource> parser = entity -> {
			parses.incrementAndGet();
			if (entity == bad) { throw failure; }
			return new Observation();
		};

		prefetch(List.of(bad, good), parser);

		assertThat(ReindexBatchPrefetch.takeResource(myTransaction, good, parser)).isInstanceOf(Observation.class);
		assertThatThrownBy(() -> ReindexBatchPrefetch.takeResource(myTransaction, bad, parser)).isSameAs(failure);
		assertThat(parses).hasValue(2);
		verifyNoInteractions(myIdHelper);
	}

	@Test
	void excludesRemoteContainedConditionalAndUntypedReferencesWithoutChangingTheirNativeProcessing() {
		Observation body = new Observation();
		for (String reference : List.of("#local", "https://example.org/fhir/Patient/one", "Patient?identifier=one", "urn:uuid:one", "one")) {
			body.addPerformer(new Reference(reference));
		}

		prefetch(List.of(entity(1001, 1L, "Observation", "one")), ignored -> body);

		verifyNoInteractions(myIdHelper);
	}

	@ParameterizedTest
	@ValueSource(ints = {64, 88, 256, 512})
	void incomingTargetsKeepTheCompleteLongIdentity(int theLength) {
		String id = "p".repeat(theLength);
		ResourceTable source = entity(1001, 1L, "Observation", "source");
		ResourceTable target = entity(1001, 2L, "Patient", id);
		when(myIdHelper.resolveResourceIdentities(eq(myPartition), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/" + id), target));

		prefetch(List.of(source), ignored -> new Observation().setSubject(new Reference("Patient/" + id)));

		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/" + id))).isSameAs(target);
		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/" + id + "x"))).isNull();
	}

	@Test
	void defaultPartitionUsesItsConfiguredScopeAndUnpersistedWritesDoNotDiscardOtherTargets() {
		ResourceTable source = mock(ResourceTable.class);
		when(source.getPersistentId()).thenReturn(new JpaPid(1001, 1L));
		ResourceTable target = entity(1001, 2L, "Patient", "target");
		when(myIdHelper.resolveResourceIdentities(eq(myPartition), any(), any()))
				.thenReturn(Map.of(new IdType("Patient/target"), target));

		ReindexBatchPrefetch.prefetch(myTransaction, List.of(source),
				ignored -> new Observation().setSubject(new Reference("Patient/target")), myContext, myIdHelper, myPartition);
		ReindexBatchPrefetch.invalidate(myTransaction, new ResourceTable());

		assertThat(ReindexBatchPrefetch.findTarget(myTransaction, myPartition, new IdType("Patient/target"))).isSameAs(target);
		verify(myIdHelper).resolveResourceIdentities(eq(myPartition), any(), any());
	}

	private void prefetch(List<ResourceTable> theEntities, Function<ResourceTable, IBaseResource> theParser) {
		ReindexBatchPrefetch.prefetch(myTransaction, theEntities, theParser, myContext, myIdHelper, RequestPartitionId.fromPartitionId(null));
	}

	private ResourceTable entity(int thePartition, long thePid, String theType, String theId) {
		ResourceTable entity = new ResourceTable();
		entity.setId(new JpaPid(thePartition, thePid));
		entity.setPartitionId(PartitionablePartitionId.with(thePartition, null));
		entity.setResourceType(theType);
		entity.setFhirId(theId);
		entity.setVersionForUnitTest(1);
		return entity;
	}
}
