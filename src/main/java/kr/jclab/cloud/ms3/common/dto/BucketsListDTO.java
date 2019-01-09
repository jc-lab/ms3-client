package kr.jclab.cloud.ms3.common.dto;

import java.util.ArrayList;
import java.util.List;

public class BucketsListDTO {
    public static class BucketSummary {
        public String bucketName;
        public Long creationTime;
        public Long lastModified;
    }

    public static class Response {
        public List<BucketSummary> list = new ArrayList<>();
    }
}
