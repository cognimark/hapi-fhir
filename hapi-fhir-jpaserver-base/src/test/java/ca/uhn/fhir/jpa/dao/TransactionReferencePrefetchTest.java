package ca.uhn.fhir.jpa.dao;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.api.svc.ResolveIdentityMode;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionReferencePrefetchTest {

	@Test
	void deletedReferenceIsNotCachedAsMissingButAbsentTargetsAre() {
		var processor = new TransactionProcessor();
		@SuppressWarnings("unchecked")
		IIdHelperService<JpaPid> helper = mock(IIdHelperService.class);
		ReflectionTestUtils.setField(processor, "myIdHelperService", helper);
		var partition = RequestPartitionId.fromPartitionId(1001);
		IIdType deletedId = new IdType("Patient/deleted");
		IIdType liveId = new IdType("Patient/live");
		IIdType absentId = new IdType("Patient/absent");
		var deleted = new ResourceTable();
		deleted.setId(new JpaPid(1001, 1L));
		deleted.setDeleted(new Date());
		var live = new ResourceTable();
		live.setId(new JpaPid(1001, 2L));
		when(helper.resolveResourceIdentities(any(), any(), any()))
				.thenReturn(new HashMap<>(Map.of(deletedId, deleted, liveId, live)));
		var transaction = new TransactionDetails();
		Map<IIdType, TransactionProcessor.PrefetchReasonEnum> reasons = new HashMap<>();
		Set<IIdType> identifiers = Set.of(deletedId, liveId, absentId);
		identifiers.forEach(id -> reasons.put(id, TransactionProcessor.PrefetchReasonEnum.REFERENCE_TARGET));

		ReflectionTestUtils.invokeMethod(processor, "doPreFetchResourcesById", transaction,
				partition, identifiers, reasons, ResolveIdentityMode.includeDeleted().cacheOk(), new HashSet<JpaPid>());

		assertThat(transaction.hasNullResolvedResourceId(deletedId)).isFalse();
		assertThat(transaction.getResolvedResourceId(deletedId)).isNull();
		assertThat(transaction.getResolvedResourceId(liveId)).isEqualTo(live.getPersistentId());
		assertThat(transaction.hasNullResolvedResourceId(absentId)).isTrue();
	}
}
