package org.horizon.cdr;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.executor.InterceptorService;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;
import ca.uhn.fhir.rest.api.server.bulk.IResourceConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EcdsBulkExportInterceptorTest {

    private final FhirContext myContext = FhirContext.forR4Cached();
    private final EcdsBulkExportInterceptor myInterceptor = new EcdsBulkExportInterceptor(
            new FakeStorage(myContext).registry(), myContext, EcdsConverterConfig.defaults());

    @Test
    void answersOnlyForTheEcdsFormat() {
        assertThat(myInterceptor.converterFor(parameters("text/csv"))).isNotNull();
        assertThat(myInterceptor.converterFor(parameters("TEXT/CSV; charset=utf-8"))).isNotNull();

        // NDJSON and anything else stay with the server's own handling.
        assertThat(myInterceptor.converterFor(parameters(Constants.CT_FHIR_NDJSON))).isNull();
        assertThat(myInterceptor.converterFor(parameters("application/json"))).isNull();
        assertThat(myInterceptor.converterFor(parameters(null))).isNull();
        assertThat(myInterceptor.converterFor(null)).isNull();
    }

    @Test
    void sharesOneConverterAcrossCallsSoItsCachesAreShared() {
        IResourceConverter first = myInterceptor.converterFor(parameters("text/csv"));
        IResourceConverter second = myInterceptor.converterFor(parameters("text/csv"));

        assertThat(first).isSameAs(second).isInstanceOf(EcdsResourceConverter.class);
    }

    @Test
    void theFormatIsConfigurable() {
        EcdsBulkExportInterceptor custom = new EcdsBulkExportInterceptor(
                new FakeStorage(myContext).registry(), myContext,
                new EcdsConverterConfig("application/vnd.horizon.ecds+csv", "konza", 10, "EcdsAudit",
                        EcdsConverterConfig.DEFAULT_MAX_FILE_BYTES, EcdsConverterConfig.DEFAULT_PAGE_SIZE));

        assertThat(custom.converterFor(parameters("application/vnd.horizon.ecds+csv"))).isNotNull();
        assertThat(custom.converterFor(parameters("text/csv"))).isNull();
    }

    @Test
    void hapiBindsTheHookAndGetsTheConverterBackThroughThePointcut() {
        // The other tests call converterFor directly, which cannot catch the failure that
        // matters most: if the method signature stops matching what the pointcut declares,
        // registerInterceptor throws and Smile fails to start. Drive it the way
        // ExpandResourcesConsumer.getConverter() and BulkExportJobParametersValidator do.
        InterceptorService service = new InterceptorService("test");
        service.registerInterceptor(myInterceptor);

        assertThat(service.hasHooks(Pointcut.STORAGE_BULK_EXPORT_RESOURCE_CONVERT)).isTrue();
        assertThat(callPointcut(service, "text/csv"))
                .isSameAs(myInterceptor.converter());
        assertThat(callPointcut(service, Constants.CT_FHIR_NDJSON)).isNull();
    }

    private static Object callPointcut(InterceptorService theService, String theOutputFormat) {
        HookParams params = new HookParams();
        params.add(BulkExportJobParameters.class, parameters(theOutputFormat));
        return theService.callHooksAndReturnObject(
                Pointcut.STORAGE_BULK_EXPORT_RESOURCE_CONVERT, params);
    }

    private static BulkExportJobParameters parameters(String theOutputFormat) {
        BulkExportJobParameters parameters = new BulkExportJobParameters();
        parameters.setOutputFormat(theOutputFormat);
        return parameters;
    }
}
