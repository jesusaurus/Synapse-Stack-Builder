package org.sagebionetworks.template.etl;

import com.amazonaws.services.cloudformation.model.Tag;
import com.google.common.collect.ImmutableMap;
import org.apache.logging.log4j.Logger;
import org.apache.velocity.app.VelocityEngine;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.template.CloudFormationClient;
import org.sagebionetworks.template.CreateOrUpdateStackRequest;
import org.sagebionetworks.template.LoggerFactory;
import org.sagebionetworks.template.StackTagsProvider;
import org.sagebionetworks.template.TemplateGuiceModule;
import org.sagebionetworks.template.config.Configuration;
import org.sagebionetworks.template.repo.kinesis.firehose.GlueTableDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_INSTANCE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_STACK;

@ExtendWith(MockitoExtension.class)
public class EtlBuilderImplTest {
    private static String STACK_NAME = "dev";
    private static String INSTANCE = "test";
    private static String version ="v1.0.0";
    @Captor
    ArgumentCaptor<CreateOrUpdateStackRequest> requestCaptor;
    @Mock
    private CloudFormationClient cloudFormationClient;
    private VelocityEngine velocityEngine = new TemplateGuiceModule().velocityEngineProvider();
    @Mock
    private Configuration mockConfig;
    @Mock
    private Logger logger;
    @Mock
    private StackTagsProvider tagsProvider;
    @Mock
    private EtlConfig etlConfig;
    @Mock
    private LoggerFactory loggerFactory;
    private EtlBuilderImpl etlBuilderImpl;
    private List<Tag> tags = new ArrayList<>();
    private List<EtlDescriptor> etlDescriptors = new ArrayList<>();
    private EtlDescriptor etlDescriptor = new EtlDescriptor();

    @BeforeEach
    public void before() {
        Tag t = new Tag().withKey("aKey").withValue("aValue");
        tags.add(t);
        etlDescriptor.setName("processAccessRecord");
        etlDescriptor.setScriptLocation("fakeBucket/");
        etlDescriptor.setScriptName("someFile.py");
        etlDescriptor.setDestinationPath("destination");
        etlDescriptor.setSourcePath("source");
        etlDescriptor.setDestinationFileFormat("json");
        etlDescriptor.setDescription("test");
        GlueTableDescriptor table = new GlueTableDescriptor();
        table.setName("someTableRef");
        table.setColumns(ImmutableMap.of("someColumn", "string"));
        etlDescriptor.setTableDescriptor(table);
        etlDescriptors.add(etlDescriptor);
        when(mockConfig.getProperty(PROPERTY_KEY_STACK)).thenReturn(STACK_NAME);
        when(mockConfig.getProperty(PROPERTY_KEY_INSTANCE)).thenReturn(INSTANCE);
        when(etlConfig.getEtlDescriptors()).thenReturn(etlDescriptors);
        when(tagsProvider.getStackTags()).thenReturn(tags);
        when(loggerFactory.getLogger(EtlBuilderImpl.class)).thenReturn(logger);
        etlBuilderImpl = new EtlBuilderImpl(cloudFormationClient, velocityEngine, mockConfig, loggerFactory, tagsProvider, etlConfig);
    }

    @Test
    public void testEtlBuildAndDeployJob() {
        String expectedStackName = new StringJoiner("-")
                .add(STACK_NAME).add(INSTANCE).add("etl").toString();
        etlBuilderImpl.buildAndDeploy(version);
        verify(cloudFormationClient).createOrUpdateStack(requestCaptor.capture());
        CreateOrUpdateStackRequest req = requestCaptor.getValue();
        JSONObject json = new JSONObject(req.getTemplateBody());
        assertEquals(expectedStackName, req.getStackName());
        assertEquals(tags, req.getTags());
        assertNotNull(req.getTemplateBody());
        JSONObject resources = json.getJSONObject("Resources");
        assertNotNull(resources);
        assertEquals(Set.of("AWSGlueJobRole", "synapsewarehouseGlueDatabase", "processAccessRecordGlueJob",
                        "someTableRefGlueTable", "processAccessRecordGlueJobTrigger"),
                resources.keySet());

        JSONObject props = resources.getJSONObject("processAccessRecordGlueJob").getJSONObject("Properties");
        assertEquals(etlDescriptor.getName(), props.get("Name"));
        assertEquals(etlDescriptor.getDescription(), props.get("Description"));
        assertEquals("{\"--enable-continuous-cloudwatch-log\":\"true\",\"--job-bookmark-option\":" +
                        "\"job-bookmark-enable\",\"--enable-metrics\":\"true\",\"--enable-spark-ui\":\"true\"," +
                        "\"--job-language\":\"python\",\"--DESTINATION_FILE_FORMAT\":\"json\",\"--S3_DESTINATION_PATH\":\"s3://dev."
                        + etlDescriptor.getDestinationPath() + "\",\"" +
                        "--S3_SOURCE_PATH\":\"s3://dev." + etlDescriptor.getSourcePath() + "\"}",
                props.getString("DefaultArguments"));

        JSONObject tableProperty = resources.getJSONObject("someTableRefGlueTable").getJSONObject("Properties");
        assertEquals("{\"Name\":\"" + etlDescriptor.getTableDescriptor().getName().toLowerCase() +
                "\",\"StorageDescriptor\":{\"Columns\":[{\"Name\":\"someColumn\"," +
                "\"Type\":\"string\"}],\"InputFormat\":\"org.apache.hadoop.mapred.TextInputFormat\",\"OutputFormat\":" +
                "\"org.apache.hadoop.hive.ql.io.IgnoreKeyTextOutputFormat\",\"SerdeInfo\":{\"SerializationLibrary\":" +
                "\"org.openx.data.jsonserde.JsonSerDe\",\"Parameters\":{\"serialization.format\":\"1\"}}," +
                "\"Compressed\":false,\"Location\":\"s3://dev.destination\"},\"PartitionKeys\":[],\"TableType\":" +
                "\"EXTERNAL_TABLE\"}", tableProperty.getString("TableInput"));

        JSONObject dataBaseProperty = resources.getJSONObject("synapsewarehouseGlueDatabase").getJSONObject("Properties");
        assertEquals("{\"Name\":\"synapsewarehouse\"}", dataBaseProperty.getString("DatabaseInput"));

        JSONObject glueJobTrigger = resources.getJSONObject("processAccessRecordGlueJobTrigger").getJSONObject("Properties");
        assertEquals("{\"Type\":\"SCHEDULED\",\"StartOnCreation\":\"true\",\"Description\":" +
                "\"Trigger for job processAccessRecord\",\"Name\":\"processAccessRecordTrigger\",\"Schedule\":" +
                "\"cron(0 * * * ? *)\",\"Actions\":[{\"JobName\":\"processAccessRecord\"}]}", glueJobTrigger.toString());
    }
}
