package com.amazonaws.xray.handlers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.amazonaws.services.xray.AWSXRayClientBuilder;
import com.amazonaws.services.xray.model.GetSamplingRulesRequest;
import com.amazonaws.services.xray.model.GetSamplingTargetsRequest;
import com.amazonaws.xray.AWSXRayRecorder;
import com.amazonaws.xray.strategy.LogErrorContextMissingStrategy;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.io.EmptyInputStream;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.HttpContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.http.AmazonHttpClient;
import com.amazonaws.http.apache.client.impl.ConnectionManagerAwareHttpClient;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.AWSXRayRecorderBuilder;
import com.amazonaws.xray.emitters.Emitter;
import com.amazonaws.xray.entities.Segment;

@FixMethodOrder(MethodSorters.JVM)
public class TracingHandlerTest {

    @Before
    public void setupAWSXRay() {
        Emitter blankEmitter = Mockito.mock(Emitter.class);
        Mockito.doReturn(true).when(blankEmitter).sendSegment(Mockito.anyObject());
        Mockito.doReturn(true).when(blankEmitter).sendSubsegment(Mockito.anyObject());
        AWSXRay.setGlobalRecorder(AWSXRayRecorderBuilder.standard().withEmitter(blankEmitter).build());
        AWSXRay.clearTraceEntity();
    }
    
    private void mockHttpClient(Object client, String responseContent) {
        AmazonHttpClient amazonHttpClient = new AmazonHttpClient(new ClientConfiguration());
        ConnectionManagerAwareHttpClient apacheHttpClient = Mockito.mock(ConnectionManagerAwareHttpClient.class);
        HttpResponse httpResponse = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
        BasicHttpEntity responseBody = new BasicHttpEntity();
        InputStream in = EmptyInputStream.INSTANCE;
        if(null != responseContent && !responseContent.isEmpty()) {
            in = new ByteArrayInputStream(responseContent.getBytes(StandardCharsets.UTF_8));
        }
        responseBody.setContent(in); 
        httpResponse.setEntity(responseBody);

        try {
            Mockito.doReturn(httpResponse).when(apacheHttpClient).execute(Mockito.any(HttpUriRequest.class), Mockito.any(HttpContext.class));
        } catch (IOException e) { 
            System.err.println("Exception during mock: " + e);
        }

        Whitebox.setInternalState(amazonHttpClient, "httpClient", apacheHttpClient);
        Whitebox.setInternalState(client, "client", amazonHttpClient);
    }

    @Test
    public void testLambdaInvokeSubsegmentContainsFunctionName() {
        // Setup test
        AWSLambda lambda = AWSLambdaClientBuilder.standard().withRequestHandlers(new TracingHandler()).withRegion(Regions.US_EAST_1).withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("fake", "fake"))).build();
        mockHttpClient(lambda, "null"); // Lambda returns "null" on successful fn. with no return value
        
        // Test logic
        Segment segment = AWSXRay.beginSegment("test");

        InvokeRequest request = new InvokeRequest();
        request.setFunctionName("testFunctionName");
        InvokeResult r = lambda.invoke(request);

        Assert.assertEquals(1, segment.getSubsegments().size());
        Assert.assertEquals("Invoke", segment.getSubsegments().get(0).getAws().get("operation"));
        Assert.assertEquals("testFunctionName", segment.getSubsegments().get(0).getAws().get("function_name"));
    }
    
    @Test
    public void testS3PutObjectSubsegmentContainsBucketName() {
        // Setup test
        AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRequestHandlers(new TracingHandler()).withRegion(Regions.US_EAST_1).withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("fake", "fake"))).build();        
        mockHttpClient(s3, null);
        
        final String BUCKET = "test-bucket", KEY = "test-key";
        // Test logic 
        Segment segment = AWSXRay.beginSegment("test");        
        s3.putObject(BUCKET, KEY, "This is a test from java");               
        Assert.assertEquals(1, segment.getSubsegments().size());
        Assert.assertEquals("PutObject", segment.getSubsegments().get(0).getAws().get("operation"));
        Assert.assertEquals(BUCKET, segment.getSubsegments().get(0).getAws().get("bucket_name"));
        Assert.assertEquals(KEY, segment.getSubsegments().get(0).getAws().get("key"));
    }
    
    @Test
    public void testS3CopyObjectSubsegmentContainsBucketName() {
        // Setup test
        final String copyResponse = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
                "<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" + 
                "<LastModified>2018-01-21T10:09:54.000Z</LastModified><ETag>&quot;31748afd7b576119d3c2471f39fc7a55&quot;</ETag>" + 
                "</CopyObjectResult>";
        AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRequestHandlers(new TracingHandler()).withRegion(Regions.US_EAST_1).withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("fake", "fake"))).build();        
        mockHttpClient(s3, copyResponse);
        
        final String BUCKET = "source-bucket", KEY = "source-key", DST_BUCKET = "dest-bucket", DST_KEY = "dest-key";
        // Test logic 
        Segment segment = AWSXRay.beginSegment("test");        
        s3.copyObject(BUCKET, KEY, DST_BUCKET, DST_KEY);               
        Assert.assertEquals(1, segment.getSubsegments().size());
        Assert.assertEquals("CopyObject", segment.getSubsegments().get(0).getAws().get("operation"));
        Assert.assertEquals(BUCKET, segment.getSubsegments().get(0).getAws().get("source_bucket_name"));
        Assert.assertEquals(KEY, segment.getSubsegments().get(0).getAws().get("source_key"));
        Assert.assertEquals(DST_BUCKET, segment.getSubsegments().get(0).getAws().get("destination_bucket_name"));
        Assert.assertEquals(DST_KEY, segment.getSubsegments().get(0).getAws().get("destination_key"));
    }
    
    @Test
    public void testSNSPublish() {
        // Setup test
    	// reference : https://docs.aws.amazon.com/sns/latest/api/API_Publish.html
        final String publishResponse = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + 
                "<PublishResponse xmlns=\"http://sns.amazonaws.com/doc/2010-03-31/\">" + 
                "<PublishResult><MessageId>94f20ce6-13c5-43a0-9a9e-ca52d816e90b</MessageId></PublishResult>" + 
                "</PublishResponse>";
        final String topicArn = "testTopicArn";
        AmazonSNS sns = AmazonSNSClientBuilder.standard().withRequestHandlers(new TracingHandler()).withRegion(Regions.US_EAST_1).withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("fake", "fake"))).build();        
        mockHttpClient(sns, publishResponse);
        // Test logic 
        Segment segment = AWSXRay.beginSegment("test");
        sns.publish(new PublishRequest(topicArn, "testMessage"));
        Assert.assertEquals(1, segment.getSubsegments().size());
        Assert.assertEquals("Publish", segment.getSubsegments().get(0).getAws().get("operation"));
        Assert.assertEquals(topicArn, segment.getSubsegments().get(0).getAws().get("topic_arn"));
    }

    @Test
    public void testShouldNotTraceXRaySamplingOperations() {
        com.amazonaws.services.xray.AWSXRay xray = AWSXRayClientBuilder.standard()
                .withRequestHandlers(new TracingHandler()).withRegion(Regions.US_EAST_1)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("fake", "fake")))
                .build();
        mockHttpClient(xray, null);

        Segment segment = AWSXRay.beginSegment("test");
        xray.getSamplingRules(new GetSamplingRulesRequest());
        Assert.assertEquals(0, segment.getSubsegments().size());

        xray.getSamplingTargets(new GetSamplingTargetsRequest());
        Assert.assertEquals(0, segment.getSubsegments().size());
    }

    @Test
    public void testShouldNotThrowContextMissingOnXRaySampling() {
        com.amazonaws.services.xray.AWSXRay xray = AWSXRayClientBuilder.standard()
                .withRequestHandlers(new TracingHandler()).withRegion(Regions.US_EAST_1)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("fake", "fake")))
                .build();
        mockHttpClient(xray, null);

        xray.getSamplingRules(new GetSamplingRulesRequest());
        xray.getSamplingTargets(new GetSamplingTargetsRequest());
    }

    @Test
    public void testRaceConditionOnRecorderInitialization() {
        AWSXRay.setGlobalRecorder(null);
        // TracingHandler will not have the initialized recorder
        AWSLambda lambda = AWSLambdaClientBuilder.standard()
                .withRequestHandlers(new TracingHandler())
                .withRegion(Regions.US_EAST_1)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("fake", "fake")))
                .build();

        mockHttpClient(lambda, "null");

        // Now init the global recorder
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.defaultRecorder();
        recorder.setContextMissingStrategy(new LogErrorContextMissingStrategy());
        AWSXRay.setGlobalRecorder(recorder);

        // Test logic
        InvokeRequest request = new InvokeRequest();
        request.setFunctionName("testFunctionName");
        InvokeResult r = lambda.invoke(request);
    }
}
