package ca.uhn.fhir.jpa.dao;

import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.ReindexOutcome;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.searchparam.extractor.ReindexBatchPrefetch;
import ca.uhn.fhir.jpa.model.entity.PartitionablePartitionId;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReindexPartitionContextTest {

	@Test
	@SuppressWarnings("unchecked")
	void reindexConsumesThePrefetchedBodyWithoutParsingAgain() {
		BaseHapiFhirResourceDao<Patient> dao = mock(BaseHapiFhirResourceDao.class, CALLS_REAL_METHODS);
		IJpaStorageResourceParser parser = mock(IJpaStorageResourceParser.class);
		ReflectionTestUtils.setField(dao, "myJpaStorageResourceParser", parser);
		ReflectionTestUtils.setField(dao, "myPartitionSettings", new PartitionSettings());
		ResourceTable entity = new ResourceTable();
		entity.setId(new JpaPid(1001, 1L));
		entity.setPartitionId(PartitionablePartitionId.with(1001, null));
		entity.setResourceType("Patient");
		entity.setFhirId("synthetic");
		entity.setVersionForUnitTest(1);
		Patient patient = new Patient();
		TransactionDetails transaction = new TransactionDetails();
		when(parser.toResource(entity, false)).thenReturn(patient);
		doReturn(entity).when(dao).updateEntity(any(), eq(patient), eq(entity), isNull(), eq(true), eq(false),
				eq(transaction), eq(true), eq(false));
		ReindexBatchPrefetch.prefetch(transaction, List.of(entity), e -> parser.toResource(e, false),
				FhirContext.forR4Cached(), mock(IIdHelperService.class), RequestPartitionId.fromPartitionId(null));

		Boolean result = ReflectionTestUtils.invokeMethod(dao, "reindexSearchParameters", entity, new ReindexOutcome(), transaction);

		assertThat(result).isTrue();
		verify(parser).toResource(entity, false);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(ints = {0, 1001, 1002})
	@SuppressWarnings("unchecked")
	void referenceTargetWritesUseThePersistedResourcePartition(Integer partition) {
		BaseHapiFhirResourceDao<Patient> dao = mock(BaseHapiFhirResourceDao.class, CALLS_REAL_METHODS);
		PartitionSettings settings = new PartitionSettings();
		settings.setDefaultPartitionId(0);
		ReflectionTestUtils.setField(dao, "myPartitionSettings", settings);
		ResourceTable entity = mock(ResourceTable.class);
		when(entity.getIdDt()).thenReturn(new IdDt("Patient/synthetic"));
		when(entity.getPartitionId()).thenReturn(partition == null ? null : new PartitionablePartitionId(partition, null));
		Patient patient = new Patient();
		TransactionDetails transaction = new TransactionDetails();
		doReturn(entity).when(dao).updateEntity(any(), eq(patient), eq(entity), isNull(), eq(true), eq(false),
				eq(transaction), eq(true), eq(false));

		ReflectionTestUtils.invokeMethod(dao, "reindexSearchParameters", patient, entity, transaction);

		ArgumentCaptor<RequestDetails> request = ArgumentCaptor.forClass(RequestDetails.class);
		verify(dao).updateEntity(request.capture(), eq(patient), eq(entity), isNull(), eq(true), eq(false),
				eq(transaction), eq(true), eq(false));
		assertThat(((SystemRequestDetails) request.getValue()).getRequestPartitionId())
				.isEqualTo(partition == null ? settings.getDefaultRequestPartitionId()
						: entity.getPartitionId().toPartitionId());
	}
}
