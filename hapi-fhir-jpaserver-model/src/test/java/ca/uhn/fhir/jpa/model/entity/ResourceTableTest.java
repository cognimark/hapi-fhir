package ca.uhn.fhir.jpa.model.entity;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.primitive.IdDt;
import jakarta.persistence.Column;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.assertj.core.api.Assertions.assertThat;

public class ResourceTableTest {

	@Test
	void resourceIdColumnsSharePrivateStorageBound() throws NoSuchFieldException {
		assertThat(ResourceTable.FHIR_ID_LENGTH).isEqualTo(512);
		assertThat(ResourceTable.class.getDeclaredField("myFhirId")
			.getAnnotation(Column.class).length()).isEqualTo(512);
		assertThat(ResourceIdentifierPatientUniqueEntity.class.getDeclaredField("myFhirId")
			.getAnnotation(Column.class).length()).isEqualTo(512);
	}

	@Test
	void preservesLongResourceIdentityInVersionedId() {
		String resourceId = "p".repeat(512);
		ResourceTable resource = new ResourceTable();
		resource.setIdForUnitTest(123L);
		resource.setFhirId(resourceId);
		resource.setResourceType("Patient");
		resource.setVersionForUnitTest(7);
		assertThat(resource.getIdDt().getValueAsString())
			.isEqualTo("Patient/" + resourceId + "/_history/7");
	}

	@Test
	public void testResourceLength() {
		for (String nextName : FhirContext.forR4().getResourceTypes()) {
			if (nextName.length() > ResourceTable.RESTYPE_LEN) {
				fail("Name " + nextName + " length of " + nextName.length() + " is > " + ResourceTable.RESTYPE_LEN);
			}
		}
	}

	@ParameterizedTest
	@CsvSource(value={
		"123, null, Patient/123/_history/1",
		"123, 123, Patient/123/_history/1",
		"123, 456, Patient/456/_history/1"
	},nullValues={"null"})
	public void testPopulateId(Long theResId, String theFhirId, String theExpected) {
		// Given
		ResourceTable t = new ResourceTable();
		t.setIdForUnitTest(theResId);
		t.setFhirId(theFhirId);
		t.setResourceType(new Patient().getResourceType().name());
		t.setVersionForUnitTest(1);

		// When
		IdDt actual = t.getIdDt();

		// Then
		assertEquals(theExpected, actual.getValueAsString());
	}
}
