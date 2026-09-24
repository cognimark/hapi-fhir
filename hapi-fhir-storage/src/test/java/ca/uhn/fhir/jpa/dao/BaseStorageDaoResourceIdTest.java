package ca.uhn.fhir.jpa.dao;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Created by Codex
class BaseStorageDaoResourceIdTest {

	private final BaseStorageDao myDao = new BaseStorageDao() {
		@Override
		protected IInterceptorBroadcaster getInterceptorBroadcaster() {
			throw new UnsupportedOperationException("Not used by ID admission");
		}

		@Override
		protected JpaStorageSettings getStorageSettings() {
			throw new UnsupportedOperationException("Not used by ID admission");
		}

		@Override
		protected String getResourceName() {
			return "Patient";
		}

		@Override
		protected FhirContext getContext() {
			return FhirContext.forR4Cached();
		}
	};

	@ParameterizedTest
	@ValueSource(ints = {1, 64, 65, 88, 128, 255, 256, 512})
	void preservesOriginalResourceIdWithinPrivateStorageBound(int theLength) {
		String resourceId = "Az09.-".repeat(86).substring(0, theLength);
		Patient patient = new Patient();
		patient.setId(resourceId);

		myDao.verifyResourceIdIsValid(patient);

		assertThat(patient.getIdElement().getIdPart()).isEqualTo(resourceId);
	}

	@ParameterizedTest
	@ValueSource(strings = {"underscore_not_allowed", "space not allowed", "nonascii-é"})
	void rejectsInvalidAlphabet(String theResourceId) {
		Patient patient = new Patient();
		patient.setId(theResourceId);
		assertThatThrownBy(() -> myDao.verifyResourceIdIsValid(patient))
			.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsResourceIdsBeyondTheStorageBoundWithoutTruncation() {
		Patient patient = new Patient();
		patient.setId("p".repeat(513));
		assertThatThrownBy(() -> myDao.verifyResourceIdIsValid(patient))
			.isInstanceOf(InvalidRequestException.class);
		assertThat(patient.getIdElement().getIdPart()).hasSize(513);
	}

	@Test
	void stillRejectsQualifiedIdWithWrongResourceType() {
		Patient patient = new Patient();
		patient.setId("Observation/" + "p".repeat(88));
		assertThatThrownBy(() -> myDao.verifyResourceIdIsValid(patient))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("HAPI-2616");
	}

	@Test
	void leavesStrictFhirPrimitiveValidationUnchanged() {
		Patient patient = new Patient();
		patient.setId("p".repeat(88));
		myDao.verifyResourceIdIsValid(patient);
		assertThat(patient.getIdElement().isIdPartValid()).isFalse();
	}

	@Test
	void stillAllowsServerAssignedIdCreation() {
		Patient patient = new Patient();
		myDao.verifyResourceIdIsValid(patient);
		assertThat(patient.getIdElement().hasIdPart()).isFalse();
	}
}
