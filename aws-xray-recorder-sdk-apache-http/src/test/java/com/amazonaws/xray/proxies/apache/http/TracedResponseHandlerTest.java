package com.amazonaws.xray.proxies.apache.http;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.ResponseHandler;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.mockito.Mockito;

import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.AWSXRayRecorderBuilder;
import com.amazonaws.xray.emitters.Emitter;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.Subsegment;

@FixMethodOrder(MethodSorters.JVM)
public class TracedResponseHandlerTest {

    @Before
    public void setupAWSXRay() {
        Emitter blankEmitter = Mockito.mock(Emitter.class);
        Mockito.doReturn(true).when(blankEmitter).sendSegment(Mockito.anyObject());
        Mockito.doReturn(true).when(blankEmitter).sendSubsegment(Mockito.anyObject());
        AWSXRay.setGlobalRecorder(AWSXRayRecorderBuilder.standard().withEmitter(blankEmitter).build());
        AWSXRay.clearTraceEntity();
    }

    @Test
    public void testHandleResponse200SetsNoFlags() {
        Segment segment = segmentInResponseToCode(200);
        Subsegment subsegment = segment.getSubsegments().get(0);
        Assert.assertFalse(subsegment.isFault());
        Assert.assertFalse(subsegment.isError());
        Assert.assertFalse(subsegment.isThrottle());
    }

    @Test
    public void testHandleResponse400SetsErrorFlag() {
        Segment segment = segmentInResponseToCode(400);
        Subsegment subsegment = segment.getSubsegments().get(0);
        Assert.assertFalse(subsegment.isFault());
        Assert.assertTrue(subsegment.isError());
        Assert.assertFalse(subsegment.isThrottle());
    }

    @Test
    public void testHandleResponse429SetsErrorAndThrottleFlag() {
        Segment segment = segmentInResponseToCode(429);
        Subsegment subsegment = segment.getSubsegments().get(0);
        Assert.assertFalse(subsegment.isFault());
        Assert.assertTrue(subsegment.isError());
        Assert.assertTrue(subsegment.isThrottle());
    }

    @Test
    public void testHandleResponse500SetsFaultFlag() {
        Segment segment = segmentInResponseToCode(500);
        Subsegment subsegment = segment.getSubsegments().get(0);
        Assert.assertTrue(subsegment.isFault());
        Assert.assertFalse(subsegment.isError());
        Assert.assertFalse(subsegment.isThrottle());
    }

    private class NoOpResponseHandler implements ResponseHandler<String> {
        public String handleResponse(HttpResponse response) {
            return "no-op";
        }
    }

    private Segment segmentInResponseToCode(int code) {
        NoOpResponseHandler responseHandler = new NoOpResponseHandler();
        TracedResponseHandler<String> tracedResponseHandler = new TracedResponseHandler<>(responseHandler);
        HttpResponse httpResponse = new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, code, ""));

        Segment segment = AWSXRay.beginSegment("test");
        AWSXRay.beginSubsegment("someHttpCall");

        try {
            tracedResponseHandler.handleResponse(httpResponse);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        AWSXRay.endSubsegment();
        AWSXRay.endSegment();
        return segment;
    }
}
