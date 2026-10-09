package ca.uhn.fhir.jpa.searchparam.extractor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.model.PersistentIdToForcedIdMap;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.ResourceLink;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.test.utilities.MockInvoker;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SearchParamExtractorServiceTest {

	private SearchParamExtractorService mySvc;
	@Mock
	private IInterceptorBroadcaster myRequestInterceptorBroadcaster;
	@Mock
	private IInterceptorBroadcaster myJpaInterceptorBroadcaster;
	@Mock
	private IIdHelperService<JpaPid> myIdHelperService;

	@BeforeEach
	public void before() {
		mySvc = new SearchParamExtractorService();
		mySvc.setInterceptorBroadcasterForUnitTest(myJpaInterceptorBroadcaster);
	}

	@Test
	public void testHandleWarnings() {
		ISearchParamExtractor.SearchParamSet<Object> searchParamSet = new ISearchParamExtractor.SearchParamSet<>();
		searchParamSet.addWarning("help i'm a bug");
		searchParamSet.addWarning("Spiff");

		AtomicInteger counter = new AtomicInteger();

		when(myJpaInterceptorBroadcaster.hasHooks(eq(Pointcut.JPA_PERFTRACE_WARNING))).thenReturn(true);
		when(myJpaInterceptorBroadcaster.getInvokersForPointcut(eq(Pointcut.JPA_PERFTRACE_WARNING))).thenReturn(MockInvoker.list((Consumer<HookParams>) params->counter.incrementAndGet()));

		ServletRequestDetails requestDetails = new ServletRequestDetails(myRequestInterceptorBroadcaster);
		SearchParamExtractorService.handleWarnings(requestDetails, myJpaInterceptorBroadcaster, searchParamSet);

		verify(myJpaInterceptorBroadcaster, times(3)).hasHooks(eq(Pointcut.JPA_PERFTRACE_WARNING));
		verify(myRequestInterceptorBroadcaster, times(2)).hasHooks(eq(Pointcut.JPA_PERFTRACE_WARNING));
		assertEquals(2, counter.get());
	}

	/**
	 * Test that findMatchingResourceLink handles null values from targetResourceIdMap.get() without throwing NPE.
	 * This scenario occurs when a ResourceLink's targetResourcePk is not present in the map.
	 */
	// Created by Claude Sonnet 4.5
	@Test
	void testFindMatchingResourceLink_whenTargetResourceIdMapReturnsNull_shouldNotThrowNPE() throws Exception {
		// Setup
		FhirContext ctx = FhirContext.forR4();
		SearchParamExtractorService svc = new SearchParamExtractorService();
		svc.setContextForUnitTest(ctx);
		svc.setIdHelperServiceForUnitTest(myIdHelperService);

		// Create PathAndRef with a reference to Patient/123
		Reference reference = new Reference("Patient/123");
		PathAndRef pathAndRef = new PathAndRef("member", "Group.member.entity", reference, false);

		// Create a ResourceLink with a target PID that won't be in the map
		ResourceTable table = new ResourceTable();
		table.setResourceType("Group");
		table.setId(JpaPid.fromId(123L));
		ResourceLink resourceLink = ResourceLink.forLogicalReference("Group.member.entity", table,
			"http://example.com", Date.from(Instant.now()));

		List<ResourceLink> existingResourceLinks = new ArrayList<>();
		existingResourceLinks.add(resourceLink);

		// Create a map that contains a different PID, so our lookup returns null
		Map<JpaPid, Optional<String>> idMap = new HashMap<>();
		JpaPid differentPid = JpaPid.fromId(888L);
		idMap.put(differentPid, Optional.of("Patient/888"));

		// Use reflection to call the private method
		Method method = SearchParamExtractorService.class.getDeclaredMethod(
			"findMatchingResourceLink",
			PathAndRef.class,
			Collection.class
		);
		method.setAccessible(true);

		// Execute - should not throw NullPointerException
		Optional<ResourceLink> result = unsafeCast(method.invoke(svc, pathAndRef, existingResourceLinks));

		// Verify - should return empty Optional since no match was found
		assertThat(result).isEmpty();
	}

	@SuppressWarnings("unchecked")
	private static <T> T unsafeCast(Object theObject) {
		return (T) theObject;
	}

	@Test
	void existingReferenceMatchesTheIdPartWithoutItsTypeSeparator() {
		mySvc.setContextForUnitTest(FhirContext.forR4Cached());
		mySvc.setIdHelperServiceForUnitTest(myIdHelperService);
		JpaPid pid = new JpaPid(1001, 123L);
		ResourceLink link = mock(ResourceLink.class);
		when(link.getTargetResourcePk()).thenReturn(pid);
		when(link.getSourcePath()).thenReturn("MedicationRequest.subject");
		when(link.getTargetResourceType()).thenReturn("Patient");
		when(myIdHelperService.translatePidsToForcedIds(Set.of(pid)))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(pid, Optional.of("Patient/synthetic"))));
		PathAndRef reference = new PathAndRef("subject", "MedicationRequest.subject", new Reference("Patient/synthetic"), false);

		Optional<ResourceLink> matched = ReflectionTestUtils.invokeMethod(
				mySvc, "findMatchingResourceLink", reference, List.of(link));

		assertThat(matched).containsSame(link);
	}

	@ParameterizedTest
	@CsvSource({
			"MedicationRequest.subject,Patient,synthetic,3,Patient/synthetic/_history/3,true",
			"MedicationRequest.encounter,Patient,synthetic,3,Patient/synthetic/_history/3,false",
			"MedicationRequest.subject,Practitioner,synthetic,3,Patient/synthetic/_history/3,false",
			"MedicationRequest.subject,Patient,different,3,Patient/synthetic/_history/3,false",
			"MedicationRequest.subject,Patient,synthetic,2,Patient/synthetic/_history/3,false",
			"MedicationRequest.subject,Patient,Synthetic,3,Patient/synthetic/_history/3,false",
			"MedicationRequest.subject,Patient,synthetic,3,https://example.org/fhir/Patient/synthetic/_history/3,true"
	})
	void matchingStillRequiresTheSamePathTypeIdAndVersion(
			String thePath, String theType, String theId, long theVersion, String theReference, boolean theMatches) {
		FhirContext context = FhirContext.forR4();
		context.getParserOptions().setStripVersionsFromReferences(false);
		mySvc.setContextForUnitTest(context);
		mySvc.setIdHelperServiceForUnitTest(myIdHelperService);
		JpaPid pid = new JpaPid(1001, 123L);
		ResourceLink link = mock(ResourceLink.class);
		when(link.getTargetResourcePk()).thenReturn(pid);
		when(link.getSourcePath()).thenReturn(thePath);
		when(link.getTargetResourceType()).thenReturn(theType);
		when(link.getTargetResourceVersion()).thenReturn(theVersion);
		when(myIdHelperService.translatePidsToForcedIds(Set.of(pid)))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(pid, Optional.of(theType + "/" + theId))));
		PathAndRef reference = new PathAndRef("subject", "MedicationRequest.subject", new Reference(theReference), false);

		Optional<ResourceLink> matched = ReflectionTestUtils.invokeMethod(
				mySvc, "findMatchingResourceLink", reference, List.of(link));

		assertThat(matched.isPresent()).isEqualTo(theMatches);
	}

	@ParameterizedTest
	@ValueSource(ints = {64, 88, 256, 512})
	void matchingPreservesLongResourceIdentities(int theLength) {
		mySvc.setContextForUnitTest(FhirContext.forR4Cached());
		mySvc.setIdHelperServiceForUnitTest(myIdHelperService);
		String id = "s".repeat(theLength);
		JpaPid pid = new JpaPid(1001, 123L);
		ResourceLink link = mock(ResourceLink.class);
		when(link.getTargetResourcePk()).thenReturn(pid);
		when(link.getSourcePath()).thenReturn("MedicationRequest.subject");
		when(link.getTargetResourceType()).thenReturn("Patient");
		when(myIdHelperService.translatePidsToForcedIds(Set.of(pid)))
				.thenReturn(new PersistentIdToForcedIdMap<>(Map.of(pid, Optional.of("Patient/" + id))));
		PathAndRef reference = new PathAndRef("subject", "MedicationRequest.subject", new Reference("Patient/" + id), false);

		Optional<ResourceLink> matched = ReflectionTestUtils.invokeMethod(
				mySvc, "findMatchingResourceLink", reference, List.of(link));

		assertThat(matched).containsSame(link);
	}
}
