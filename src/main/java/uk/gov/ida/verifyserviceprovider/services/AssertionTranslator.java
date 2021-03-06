package uk.gov.ida.verifyserviceprovider.services;

import org.joda.time.DateTime;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import uk.gov.ida.saml.security.SamlAssertionsSignatureValidator;
import uk.gov.ida.verifyserviceprovider.dto.LevelOfAssurance;
import uk.gov.ida.verifyserviceprovider.dto.TranslatedResponseBody;
import uk.gov.ida.verifyserviceprovider.exceptions.SamlResponseValidationException;
import uk.gov.ida.verifyserviceprovider.utils.DateTimeComparator;
import uk.gov.ida.verifyserviceprovider.validators.IssueInstantValidator;

import java.util.List;

import static java.util.Optional.ofNullable;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.format.ISODateTimeFormat.dateHourMinuteSecond;
import static org.opensaml.saml.saml2.core.SubjectConfirmation.METHOD_BEARER;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.ACCOUNT_CREATION;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.SUCCESS_MATCH;

public class AssertionTranslator {
    private final String verifyServiceProviderEntityId;
    private final SamlAssertionsSignatureValidator assertionsSignatureValidator;
    private final IssueInstantValidator issueInstantValidator;
    private final DateTimeComparator dateTimeComparator;

    public AssertionTranslator(String verifyServiceProviderEntityId,
                               SamlAssertionsSignatureValidator assertionsSignatureValidator,
                               IssueInstantValidator issueInstantValidator, DateTimeComparator dateTimeComparator) {
        this.verifyServiceProviderEntityId = verifyServiceProviderEntityId;
        this.assertionsSignatureValidator = assertionsSignatureValidator;
        this.issueInstantValidator = issueInstantValidator;
        this.dateTimeComparator = dateTimeComparator;
    }

    public TranslatedResponseBody translate(List<Assertion> assertions, String expectedInResponseTo, LevelOfAssurance expectedLevelOfAssurance) {
        if (assertions == null || assertions.isEmpty() || assertions.size() > 1) {
            throw new SamlResponseValidationException("Exactly one assertion is expected.");
        }

        Assertion assertion = assertions.get(0);

        issueInstantValidator.validate(assertion.getIssueInstant());
        NameID nameID = validateSubject(expectedInResponseTo, assertion.getSubject());
        validateConditions(assertion.getConditions());

        assertionsSignatureValidator.validate(assertions, IDPSSODescriptor.DEFAULT_ELEMENT_NAME);

        List<AuthnStatement> authnStatements = assertion.getAuthnStatements();
        if (authnStatements == null || authnStatements.size() != 1) {
            throw new SamlResponseValidationException("Exactly one authn statement is expected.");
        }

        AuthnStatement authnStatement = authnStatements.get(0);
        validateAuthnInstant(authnStatement.getAuthnInstant());
        String levelOfAssuranceString = ofNullable(authnStatement.getAuthnContext())
            .map(AuthnContext::getAuthnContextClassRef)
            .map(AuthnContextClassRef::getAuthnContextClassRef)
            .orElseThrow(() -> new SamlResponseValidationException("Expected a level of assurance."));

        LevelOfAssurance levelOfAssurance;
        try {
            levelOfAssurance = LevelOfAssurance.fromSamlValue(levelOfAssuranceString);
        } catch (Exception ex) {
            throw new SamlResponseValidationException("Level of assurance '" + levelOfAssuranceString + "' is not supported.");
        }

        if (expectedLevelOfAssurance.isGreaterThan(levelOfAssurance)) {
            throw new SamlResponseValidationException(
                    String.format("Expected Level of Assurance to be at least %s, but was %s", expectedLevelOfAssurance, levelOfAssurance));
        }

        List<AttributeStatement> attributeStatements = assertion.getAttributeStatements();
        if (attributeStatements.isEmpty()) {
            return new TranslatedResponseBody(
                SUCCESS_MATCH,
                nameID.getValue(),
                levelOfAssurance,
                null
            );
        } else {
            // Assume it is user account creation
            return new TranslatedResponseBody(
                ACCOUNT_CREATION,
                nameID.getValue(),
                levelOfAssurance,
                AttributeTranslationService.translateAttributes(attributeStatements.get(0))
            );
        }
    }

    private void validateConditions(Conditions conditionsElement) {
        if (conditionsElement == null) {
            throw new SamlResponseValidationException("Conditions is missing from the assertion.");
        }

        validateNotBefore(conditionsElement.getNotBefore());

        DateTime notOnOrAfter = conditionsElement.getNotOnOrAfter();
        if (notOnOrAfter != null) {
            validateNotOnOrAfter(notOnOrAfter);
        }

        if (conditionsElement.getProxyRestriction() != null) {
            throw new SamlResponseValidationException("Conditions should not contain proxy restriction element.");
        }

        if (conditionsElement.getOneTimeUse() != null) {
            throw new SamlResponseValidationException("Conditions should not contain one time use element.");
        }

        List<AudienceRestriction> audienceRestrictions = conditionsElement.getAudienceRestrictions();
        if (audienceRestrictions == null || audienceRestrictions.size() != 1) {
            throw new SamlResponseValidationException("Exactly one audience restriction is expected.");
        }

        List<Audience> audiences = audienceRestrictions.get(0).getAudiences();
        if (audiences == null || audiences.size() != 1) {
            throw new SamlResponseValidationException("Exactly one audience is expected.");
        }

        String audience = audiences.get(0).getAudienceURI();
        if (!verifyServiceProviderEntityId.equals(audience)) {
            throw new SamlResponseValidationException("Audience must match entity ID. Expected " + verifyServiceProviderEntityId + " but was " + audience);
        }
    }

    private NameID validateSubject(String expectedInResponseTo, Subject subject) {
        if (subject == null) {
            throw new SamlResponseValidationException("Subject is missing from the assertion.");
        }

        if (subject.getSubjectConfirmations().size() != 1) {
            throw new SamlResponseValidationException("Exactly one subject confirmation is expected.");
        }

        SubjectConfirmation subjectConfirmation = subject.getSubjectConfirmations().get(0);
        if (!METHOD_BEARER.equals(subjectConfirmation.getMethod())) {
            throw new SamlResponseValidationException("Subject confirmation method must be 'bearer'.");
        }

        SubjectConfirmationData subjectConfirmationData = subjectConfirmation.getSubjectConfirmationData();
        if (subjectConfirmationData == null) {
            throw new SamlResponseValidationException("Subject confirmation data is missing from the assertion.");
        }

        validateNotBefore(subjectConfirmationData.getNotBefore());

        DateTime notOnOrAfter = subjectConfirmationData.getNotOnOrAfter();
        if (notOnOrAfter == null) {
            throw new SamlResponseValidationException("Subject confirmation data must contain 'NotOnOrAfter'.");
        }

        validateNotOnOrAfter(notOnOrAfter);

        String actualInResponseTo = subjectConfirmationData.getInResponseTo();
        if (actualInResponseTo == null) {
            throw new SamlResponseValidationException("Subject confirmation data must contain 'InResponseTo'.");
        }

        if (!expectedInResponseTo.equals(actualInResponseTo)) {
            throw new SamlResponseValidationException("'InResponseTo' must match requestId. Expected " + expectedInResponseTo + " but was " + actualInResponseTo);
        }

        NameID nameID = subject.getNameID();
        if (nameID == null) {
            throw new SamlResponseValidationException("NameID is missing from the subject of the assertion.");
        }
        return nameID;
    }

    private void validateNotOnOrAfter(DateTime notOnOrAfter) {
        DateTime now = DateTime.now();
        if (!dateTimeComparator.isBeforeFuzzy(now, notOnOrAfter)) {
            throw new SamlResponseValidationException("Assertion is not valid on or after "
                    + notOnOrAfter.withZone(UTC).toString(dateHourMinuteSecond())
            );
        }
    }

    private void validateAuthnInstant(DateTime authnInstant) {
        if (!dateTimeComparator.isBeforeNowFuzzy(authnInstant)) {
            throw new SamlResponseValidationException("AuthnInstant is in the future " +
                    authnInstant.withZone(UTC).toString(dateHourMinuteSecond())
            );
        }
    }

    private void validateNotBefore(DateTime notBefore) {
        if (notBefore != null && !dateTimeComparator.isAfterFuzzy(DateTime.now(), notBefore)) {
            throw new SamlResponseValidationException("Assertion is not valid before " + notBefore.withZone(UTC).toString(dateHourMinuteSecond()));
        }
    }
}
