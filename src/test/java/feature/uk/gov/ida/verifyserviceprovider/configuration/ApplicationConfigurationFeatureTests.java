package feature.uk.gov.ida.verifyserviceprovider.configuration;

import common.uk.gov.ida.verifyserviceprovider.utils.EnvironmentHelper;
import io.dropwizard.logging.DefaultLoggingFactory;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit.DropwizardAppRule;
import keystore.KeyStoreResource;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.gov.ida.verifyserviceprovider.VerifyServiceProviderApplication;
import uk.gov.ida.verifyserviceprovider.configuration.MetadataUri;
import uk.gov.ida.verifyserviceprovider.configuration.VerifyServiceProviderConfiguration;

import java.util.HashMap;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static keystore.builders.KeyStoreResourceBuilder.aKeyStoreResource;
import static org.apache.xml.security.utils.Base64.decode;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PRIVATE_ENCRYPTION_KEY;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PRIVATE_SIGNING_KEY;
import static uk.gov.ida.saml.core.test.builders.CertificateBuilder.aCertificate;

public class ApplicationConfigurationFeatureTests {

    private DropwizardAppRule<VerifyServiceProviderConfiguration> application;
    private EnvironmentHelper environmentHelper = new EnvironmentHelper();

    @Before
    public void setUp() {
        KeyStoreResource keyStoreResource = aKeyStoreResource()
            .withCertificate("any-alias", aCertificate().build().getCertificate())
            .build();
        keyStoreResource.create();
        application = new DropwizardAppRule<>(
            VerifyServiceProviderApplication.class,
            resourceFilePath("verify-service-provider-test.yml"),
            ConfigOverride.config("verifyHubMetadata.trustStorePath", keyStoreResource.getAbsolutePath()),
            ConfigOverride.config("verifyHubMetadata.trustStorePassword", keyStoreResource.getPassword())
        );
    }

    @After
    public void cleanup() {
        application.getTestSupport().after();
        environmentHelper.cleanEnv();
    }

    @Test
    public void applicationShouldStartUp() throws Exception {
        environmentHelper.setEnv(new HashMap<String, String>() {{
            put("PORT", "50555");
            put("LOG_LEVEL", "ERROR");
            put("HUB_SSO_LOCATION", "some-hub-sso-location");
            put("HUB_METADATA_URL", MetadataUri.PRODUCTION.getUri().toString());
            put("MSA_METADATA_URL", "some-msa-metadata-url");
            put("MSA_ENTITY_ID", "some-msa-entity-id");
            put("SAML_SIGNING_KEY", TEST_RP_PRIVATE_SIGNING_KEY);
            put("SAML_PRIMARY_ENCRYPTION_KEY", TEST_RP_PRIVATE_ENCRYPTION_KEY);
            put("SAML_SECONDARY_ENCRYPTION_KEY", TEST_RP_PRIVATE_ENCRYPTION_KEY);
            put("CLOCK_SKEW", "PT5s");
        }});

        application.getTestSupport().before();

        VerifyServiceProviderConfiguration configuration = application.getConfiguration();

        assertThat(application.getLocalPort()).isEqualTo(50555);
        assertThat(((DefaultLoggingFactory) configuration.getLoggingFactory()).getLevel().toString()).isEqualTo("ERROR");
        assertThat(configuration.getHubSsoLocation().toString()).isEqualTo("some-hub-sso-location");
        assertThat(configuration.getVerifyHubMetadata().getUri().toString()).isEqualTo(MetadataUri.PRODUCTION.getUri().toString());
        assertThat(configuration.getVerifyHubMetadata().getExpectedEntityId()).isEqualTo("https://signin.service.gov.uk");
        assertThat(configuration.getMsaMetadata().getExpectedEntityId()).isEqualTo("some-msa-entity-id");

        assertThat(configuration.getMsaMetadata().getUri().toString()).isEqualTo("some-msa-metadata-url");
        assertThat(configuration.getSamlSigningKey().getEncoded()).isEqualTo(decode(TEST_RP_PRIVATE_SIGNING_KEY));
        assertThat(configuration.getSamlPrimaryEncryptionKey().getEncoded()).isEqualTo(decode(TEST_RP_PRIVATE_ENCRYPTION_KEY));
        assertThat(configuration.getSamlSecondaryEncryptionKey().getEncoded()).isEqualTo(decode(TEST_RP_PRIVATE_ENCRYPTION_KEY));
        assertThat(configuration.getClockSkew()).isEqualTo(Duration.standardSeconds(5));
    }
}
