package com.emc.vipr.services.s3;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import org.junit.Before;

public class NamespaceTest extends BasicS3Test {
    private static final String TEST_BUCKET = "basic-s3-namespace-tests";

    @Override
    @Before
    public void setUp() throws Exception {
        vipr = S3ClientFactory.getS3Client(true);
        try {
            vipr.createBucket(TEST_BUCKET);
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 409) {
                // Ignore; bucket exists;
            } else {
                throw e;
            }
        }
    }
}
