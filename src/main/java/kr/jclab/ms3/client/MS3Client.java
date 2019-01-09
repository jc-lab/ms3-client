/*
 * Copyright 2018 JC-Lab. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kr.jclab.ms3.client;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.annotation.SdkInternalApi;
import com.amazonaws.annotation.ThreadSafe;
import com.amazonaws.services.s3.*;
import com.amazonaws.services.s3.internal.ServiceUtils;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.analytics.AnalyticsConfiguration;
import com.amazonaws.services.s3.model.inventory.InventoryConfiguration;
import com.amazonaws.services.s3.model.metrics.MetricsConfiguration;
import com.amazonaws.services.s3.waiters.AmazonS3Waiters;
import com.amazonaws.util.StringUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.jclab.ms3.common.dto.*;
import org.apache.commons.logging.Log;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.entity.AbstractHttpEntity;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

/*
 * Diary in source #DI2S-JICHAN #20181225
 *
 * Oh holy night~ Merry christmas!
 *
 * 2018여년 전 예수님이 우리를 이해하시기 위해
 * 이 땅에서 가장 낮은 곳, 목수의 아들로, 마구간의 말 구유 속에서,
 * 땀과 먼지로 얼룩진 목자들과, 이방인이었던 동방박사들의 환영을 받으며,
 * 어린시절부터 청년을 지나 인간의 삶을 경험하시러 이 땅가운데 오셨습니다.
 *
 * 우리를 이해하시고 위로하시러, 그리고 우리를 구원하시러 온 예수님께 감사하고
 * 생신 축하 드립니다 :)
 */

/**
 * Amazon S3의 AmazonS3 클래스와 호환되는 MS3Client구현입니다.
 */
@ThreadSafe
public class MS3Client implements AmazonS3 {
    private final String MediaType_JSON = "application/json";

    private Log log = null;
    private volatile AmazonS3Waiters waiters = null;

    // END BY '/'
    private final String m_serverUrl;

    // Thread safe
    private HttpClient m_httpClient;

    private MS3Client() throws NotImplementedException {
        m_serverUrl = null;
        throw new NotImplementedException();
    }

    @SdkInternalApi
    MS3Client(String serverUrl, HttpClient httpClient) {
        m_serverUrl = serverUrl;
        m_httpClient = httpClient;
    }

    public static MS3ClientBuilder builder() {
        return MS3ClientBuilder.standard();
    }

    // ================================================== Implementations ==================================================

    private class ApiRequestContext<T> {
        public HttpUriRequest httpRequest;
        public HttpResponse httpResponse = null;
        public T responseBody = null;
        private Class<T> objectClass;

        public ApiRequestContext(HttpUriRequest httpRequest, Class<T> objectClass) {
            this.httpRequest = httpRequest;
            this.objectClass = objectClass;
        }
    }

    @Override
    public AmazonS3Waiters waiters() {
        if (waiters == null) {
            synchronized (this) {
                if (waiters == null) {
                    waiters = new AmazonS3Waiters(this);
                }
            }
        }
        return waiters;
    }

    /**
     * Main implementation
     * @param bucketName
     * @param key
     * @return
     */
    @Override
    public URL getUrl(String bucketName, String key) {
        HttpUriRequest httpRequest = new HttpGet(m_serverUrl + "api/bucket/generateuri/" + bucketName + "/" + key);
        HttpResponse httpResponse = null;
        try {
            int statusCode;
            httpRequest.addHeader("Accept", MediaType_JSON);
            httpResponse = m_httpClient.execute(httpRequest);
            statusCode = httpResponse.getStatusLine().getStatusCode();
            if(statusCode >= 200 && statusCode < 400) {
                ObjectMapper objectMapper = new ObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
                InputStream inputStream = httpResponse.getEntity().getContent();
                GenerateUriDTO.Response responseBody = objectMapper.readValue(inputStream, GenerateUriDTO.Response.class);
                String uri = responseBody.uri;
                return new URL(new URL(m_serverUrl), uri);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            HttpClientUtils.closeQuietly(httpResponse);
        }
        return null;
    }

    /**
     * Main implementation
     */
    @Override
    public void shutdown() {
    }

    private <T> int apiRequestSync(ApiRequestContext<T> context) throws IOException {
        return apiRequestSync(context, true);
    }

    private <T> int apiRequestSync(ApiRequestContext<T> context, boolean closeHttpSession) throws IOException {
        int statusCode = 0;
        try {
            context.httpRequest.addHeader("Accept", MediaType_JSON);
            context.httpResponse = m_httpClient.execute(context.httpRequest);
            statusCode = context.httpResponse.getStatusLine().getStatusCode();
            if(statusCode >= 200 && statusCode < 400) {
                ObjectMapper objectMapper = new ObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
                Header encoding = context.httpResponse.getEntity().getContentEncoding();
                String body = org.apache.commons.io.IOUtils.toString(context.httpResponse.getEntity().getContent(), encoding != null ? encoding.getValue() : "UTF-8");
                if(body != null && body.length() > 0) {
                    context.responseBody = objectMapper.readValue(body, context.objectClass);
                }
            }
        } catch (IOException e) {
            throw e;
        } finally {
            if(closeHttpSession) {
                HttpClientUtils.closeQuietly(context.httpResponse);
            }
        }
        return statusCode;
    }

    private static boolean isHttpStatusSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 400;
    }

    private ApiRequestContext<ListObjectsDTO.Response> commonListObjects(List<S3ObjectSummary> objectSummaries, String bucket) throws SdkClientException {
        HttpUriRequest httpRequest = new HttpGet(m_serverUrl + "api/bucket/list/" + bucket);
        try {
            ApiRequestContext<ListObjectsDTO.Response> apiRequestContext = new ApiRequestContext(httpRequest, ListObjectsDTO.Response.class);
            int statusCode = apiRequestSync(apiRequestContext);
            if(isHttpStatusSuccess(statusCode)) {
                for (ListObjectsDTO.ObjectSummary item : apiRequestContext.responseBody.list) {
                    S3ObjectSummary s3ObjectSummary = new S3ObjectSummary();
                    s3ObjectSummary.setBucketName(item.bucketName);
                    s3ObjectSummary.setKey(item.key);
                    s3ObjectSummary.setSize(item.size);
                    s3ObjectSummary.setLastModified(new Date(item.lastModified));
                    objectSummaries.add(s3ObjectSummary);
                }
            }
            return apiRequestContext;
        } catch (IOException e) {
            throw new SdkClientException(e);
        }
    }

    /**
     *
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public List<Bucket> listBuckets() throws SdkClientException,
            AmazonServiceException {
        return listBuckets(new ListBucketsRequest());
    }

    /**
     * Main implementation
     * @param listBucketsRequest
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public List<Bucket> listBuckets(ListBucketsRequest listBucketsRequest)
            throws SdkClientException, AmazonServiceException {
        HttpUriRequest httpRequest = new HttpGet(m_serverUrl + "api/buckets/list");
        HttpResponse httpResponse = null;
        try {
            ApiRequestContext<BucketsListDTO.Response> apiRequestContext = new ApiRequestContext(httpRequest, BucketsListDTO.Response.class);
            int statusCode = apiRequestSync(apiRequestContext);
            if(isHttpStatusSuccess(statusCode)) {
                List<Bucket> bucketList = new ArrayList();
                for (BucketsListDTO.BucketSummary bucketSummary : apiRequestContext.responseBody.list) {
                    Bucket bucket = new Bucket(bucketSummary.bucketName);
                    bucket.setCreationDate(new Date(bucketSummary.creationTime));
                    bucketList.add(bucket);
                }
                return bucketList;
            }

            throw new SdkClientException("Error code: " + statusCode + " / " + apiRequestContext.responseBody);
        } catch (IOException e) {
            throw new SdkClientException(e);
        }
    }

    /**
     * Main implementation
     * @param createBucketRequest
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public Bucket createBucket(CreateBucketRequest createBucketRequest)
            throws SdkClientException, AmazonServiceException {
        HttpUriRequest httpRequest = new HttpPut(m_serverUrl + "api/buckets/create/" + createBucketRequest.getBucketName());
        try {
            ApiRequestContext<ResultBase> apiRequestContext = new ApiRequestContext(httpRequest, ResultBase.class);
            int statusCode = apiRequestSync(apiRequestContext);
            if(isHttpStatusSuccess(statusCode)) {
                Bucket bucket = new Bucket(createBucketRequest.getBucketName());
                return bucket;
            }
            throw new SdkClientException("Error code: " + statusCode + " / " + apiRequestContext.responseBody);
        } catch (IOException e) {
            throw new SdkClientException(e);
        }
        }

    /**
     *
     * @param bucketName
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public Bucket createBucket(String bucketName)
            throws SdkClientException, AmazonServiceException {
        return createBucket(new CreateBucketRequest(bucketName));
    }

    /**
     * Main implementation
     * @param listObjectsRequest
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public ObjectListing listObjects(ListObjectsRequest listObjectsRequest)
            throws SdkClientException, AmazonServiceException {
        ObjectListing objectListing = new ObjectListing();
        List<S3ObjectSummary> objectSummaries = objectListing.getObjectSummaries();
        String bucketName = (listObjectsRequest.getPrefix() != null ? listObjectsRequest.getPrefix() : "") + listObjectsRequest.getBucketName();
        commonListObjects(objectSummaries, bucketName);
        return objectListing;
    }

    /**
     * Main implementation
     * @param listObjectsRequest
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public ListObjectsV2Result listObjectsV2(ListObjectsV2Request listObjectsRequest) throws SdkClientException,
            AmazonServiceException {
        ListObjectsV2Result result = new ListObjectsV2Result();
        List<S3ObjectSummary> objectSummaries = result.getObjectSummaries();
        String bucketName = (listObjectsRequest.getPrefix() != null ? listObjectsRequest.getPrefix() : "") + listObjectsRequest.getBucketName();
        result.setPrefix(listObjectsRequest.getPrefix());
        result.setBucketName(listObjectsRequest.getBucketName());
        commonListObjects(objectSummaries, bucketName);
        return result;
    }

    /**
     * Main implementation
     * @param getObjectMetadataRequest
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public ObjectMetadata getObjectMetadata(GetObjectMetadataRequest getObjectMetadataRequest)
            throws SdkClientException, AmazonServiceException {
        HttpUriRequest httpRequest = new HttpGet(m_serverUrl + "api/bucket/metadata/" + getObjectMetadataRequest.getBucketName() + "/" + getObjectMetadataRequest.getKey());
        try {
            ApiRequestContext<kr.jclab.ms3.common.model.ObjectMetadata> apiRequestContext = new ApiRequestContext(httpRequest, kr.jclab.ms3.common.model.ObjectMetadata.class);
            int statusCode = apiRequestSync(apiRequestContext);
            if(isHttpStatusSuccess(statusCode)) {
                return apiRequestContext.responseBody;
            }
            throw new SdkClientException("Error code: " + statusCode + " / " + apiRequestContext.responseBody);
        } catch (IOException e) {
            throw new SdkClientException(e);
        }
    }

    /**
     * Main implementation
     * @param getObjectRequest
     * @return
     * @throws SdkClientException
     * @throws AmazonServiceException
     */
    @Override
    public S3Object getObject(GetObjectRequest getObjectRequest)
            throws SdkClientException, AmazonServiceException {
        HttpUriRequest httpRequest = new HttpGet(m_serverUrl + "api/bucket/object/" + getObjectRequest.getBucketName() + "/" + getObjectRequest.getKey());
        HttpResponse httpResponse = null;
        boolean success = false;
        try {
            int statusCode;
            httpRequest.addHeader("Accept", "*/*");
            httpResponse = m_httpClient.execute(httpRequest);
            statusCode = httpResponse.getStatusLine().getStatusCode();
            success = isHttpStatusSuccess(statusCode);
            if(isHttpStatusSuccess(statusCode)) {
                S3Object s3Object = new S3Object();
                ObjectMapper objectMapper = new ObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
                int metadataSize = Integer.parseInt(httpResponse.getFirstHeader("MS3-METADATA-SIZE").getValue());
                int metadataRemain = metadataSize;
                byte[] metadataBin = new byte[metadataSize];
                InputStream inputStream = httpResponse.getEntity().getContent();
                int readlen;
                while((metadataRemain > 0) && ((readlen = inputStream.read(metadataBin, metadataSize - metadataRemain, metadataRemain)) > 0)) {
                    metadataRemain -= readlen;
                }
                s3Object.setBucketName(getObjectRequest.getBucketName());
                s3Object.setKey(getObjectRequest.getKey());
                if(metadataBin.length > 0) {
                s3Object.setObjectMetadata(objectMapper.readValue(metadataBin, kr.jclab.ms3.common.model.ObjectMetadata.class));
                }
                s3Object.setObjectContent(httpResponse.getEntity().getContent());
                return s3Object;
            }
            throw new SdkClientException("Error code: " + statusCode);
        } catch (IOException e) {
            throw new SdkClientException(e);
        } finally {
            if(!success)
                HttpClientUtils.closeQuietly(httpResponse);
        }
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest putObjectRequest)
            throws SdkClientException, AmazonServiceException {
        PutObjectResult result = new PutObjectResult();
        HttpPut httpRequest = new HttpPut(m_serverUrl + "api/bucket/object/" + putObjectRequest.getBucketName() + "/" + putObjectRequest.getKey());
        HttpResponse httpResponse = null;
        boolean success = false;
        try {
            ApiRequestContext<PutObjectDTO.Response> apiRequestContext = new ApiRequestContext(httpRequest, PutObjectDTO.Response.class);
            ObjectMapper objectMapper = new ObjectMapper().configure(JsonGenerator.Feature.IGNORE_UNKNOWN, true);
            int statusCode;
            final byte[] metadataBin = (putObjectRequest.getMetadata() != null) ? objectMapper.writeValueAsBytes(putObjectRequest.getMetadata()) : null;
            AbstractHttpEntity entity = new AbstractHttpEntity() {
                public boolean isRepeatable() {
                    return false;
                }

                public long getContentLength() {
                    return -1;
                }

                public boolean isStreaming() {
                    return true;
                }

                public InputStream getContent() throws IOException {
                    // Should be implemented as well but is irrelevant for this case
                    throw new UnsupportedOperationException();
                }

                @Override
                public void writeTo(OutputStream outstream) throws IOException {
                    int readlen;
                    byte[] buffer = new byte[1048576];
                    InputStream inputStream = putObjectRequest.getInputStream();
                    if(metadataBin != null) {
                        outstream.write(metadataBin);
                    }
                    try {
                        if (inputStream == null) {
                            inputStream = new FileInputStream(putObjectRequest.getFile());
                        }
                        while ((readlen = inputStream.read(buffer)) > 0) {
                            outstream.write(buffer, 0, readlen);
                        }
                    } finally {
                        if (inputStream != null) {
                            try { inputStream.close(); } catch (IOException closee) { }
                        }
                    }
                }
            };
            httpRequest.addHeader("Accept", MediaType_JSON);
            httpRequest.setEntity(entity);
            httpRequest.addHeader("MS3-METADATA-SIZE", Integer.toString((metadataBin != null) ? metadataBin.length : 0));
            statusCode = apiRequestSync(apiRequestContext);
            if(isHttpStatusSuccess(statusCode)) {
                if(metadataBin != null) {
                    result.setMetadata(putObjectRequest.getMetadata());
                }
                return result;
        }
            throw new SdkClientException("Error code: " + statusCode);
        } catch (IOException e) {
            throw new SdkClientException(e);
        } finally {
            if(!success)
        HttpClientUtils.closeQuietly(httpResponse);
        }
    }

    //region Sub implementions
    @Override
    public ObjectListing listObjects(String bucketName)
            throws SdkClientException, AmazonServiceException {
        return listObjects(new ListObjectsRequest(bucketName, null, null, null, null));
    }

    @Override
    public ObjectListing listObjects(String bucketName, String prefix)
            throws SdkClientException, AmazonServiceException {
        return listObjects(new ListObjectsRequest(bucketName, prefix, null, null, null));
    }

    @Override
    public ListObjectsV2Result listObjectsV2(String bucketName)
            throws SdkClientException, AmazonServiceException {
        return listObjectsV2(new ListObjectsV2Request().withBucketName(bucketName));
    }

    @Override
    public ListObjectsV2Result listObjectsV2(String bucketName, String prefix)
            throws SdkClientException, AmazonServiceException {
        return listObjectsV2(new ListObjectsV2Request().withBucketName(bucketName).withPrefix(prefix));
    }

    @Override
    public PutObjectResult putObject(String bucketName, String key, File file)
            throws SdkClientException, AmazonServiceException {
        return putObject(new PutObjectRequest(bucketName, key, file)
                .withMetadata(new ObjectMetadata()));
    }

    @Override
    public PutObjectResult putObject(String bucketName, String key, InputStream input, ObjectMetadata metadata)
            throws SdkClientException, AmazonServiceException {
        return putObject(new PutObjectRequest(bucketName, key, input, metadata));
    }

    @Override
    public PutObjectResult putObject(String bucketName, String key, String content)
            throws AmazonServiceException, SdkClientException {
        //rejectNull(bucketName, "Bucket name must be provided");
        //rejectNull(key, "Object key must be provided");
        //rejectNull(content, "String content must be provided");

        byte[] contentBytes = content.getBytes(StringUtils.UTF8);

        InputStream is = new ByteArrayInputStream(contentBytes);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("text/plain");
        metadata.setContentLength(contentBytes.length);

        return putObject(new PutObjectRequest(bucketName, key, is, metadata));
    }
    @Override
    public ObjectMetadata getObjectMetadata(String bucketName, String key)
            throws SdkClientException, AmazonServiceException {
        return getObjectMetadata(new GetObjectMetadataRequest(bucketName, key));
    }

    @Override
    public S3Object getObject(String bucketName, String key) throws SdkClientException,
            AmazonServiceException {
        return getObject(new GetObjectRequest(bucketName, key));
    }

    @Override
    public ObjectMetadata getObject(GetObjectRequest getObjectRequest, File destinationFile)
            throws SdkClientException, AmazonServiceException {
        //rejectNull(destinationFile, "The destination file parameter must be specified when downloading an object directly to a file");

        S3Object s3Object = ServiceUtils.retryableDownloadS3ObjectToFile(destinationFile, new ServiceUtils.RetryableS3DownloadTask() {

            @Override
            public S3Object getS3ObjectStream() {
                return getObject(getObjectRequest);
            }

            @Override
            public boolean needIntegrityCheck() {
                return false; // return !skipMd5CheckStrategy.skipClientSideValidationPerRequest(getObjectRequest);
            }

        }, ServiceUtils.OVERWRITE_MODE);
        // getObject can return null if constraints were specified but not met
        if (s3Object == null) return null;

        return s3Object.getObjectMetadata();
    }

    @Override
    public String getObjectAsString(String bucketName, String key)
            throws AmazonServiceException, SdkClientException {
        //rejectNull(bucketName, "Bucket name must be provided");
        //rejectNull(key, "Object key must be provided");

        S3Object object = getObject(bucketName, key);
        try {
            return com.amazonaws.util.IOUtils.toString(object.getObjectContent());
        } catch (IOException e) {
            throw new SdkClientException("Error streaming content from S3 during download");
        } finally {
            com.amazonaws.util.IOUtils.closeQuietly(object, log);
        }
    }
    //endregion

    //region Not implemented

    /**
     * Not implemented
     */
    @Override
    public void setEndpoint(String endpoint) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setRegion(com.amazonaws.regions.Region region) throws IllegalArgumentException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setS3ClientOptions(S3ClientOptions clientOptions) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public @Deprecated
    void changeObjectStorageClass(String bucketName, String key, StorageClass newStorageClass)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public @Deprecated
    void setObjectRedirectLocation(String bucketName, String key, String newRedirectLocation)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public ObjectListing listNextBatchOfObjects(ObjectListing previousObjectListing)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public ObjectListing listNextBatchOfObjects(
            ListNextBatchOfObjectsRequest listNextBatchOfObjectsRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public VersionListing listVersions(String bucketName, String prefix)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public VersionListing listNextBatchOfVersions(VersionListing previousVersionListing)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public VersionListing listNextBatchOfVersions(
            ListNextBatchOfVersionsRequest listNextBatchOfVersionsRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public VersionListing listVersions(String bucketName, String prefix,
                                       String keyMarker, String versionIdMarker, String delimiter, Integer maxResults)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public VersionListing listVersions(ListVersionsRequest listVersionsRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public Owner getS3AccountOwner() throws SdkClientException,
            AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public Owner getS3AccountOwner(GetS3AccountOwnerRequest getS3AccountOwnerRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public @Deprecated
    boolean doesBucketExist(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public boolean doesBucketExistV2(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public HeadBucketResult headBucket(HeadBucketRequest headBucketRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public String getBucketLocation(String bucketName) throws SdkClientException,
            AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public String getBucketLocation(GetBucketLocationRequest getBucketLocationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public @Deprecated
    Bucket createBucket(String bucketName, Region region)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public @Deprecated
    Bucket createBucket(String bucketName, String region)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public AccessControlList getObjectAcl(String bucketName, String key)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public AccessControlList getObjectAcl(String bucketName, String key, String versionId)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public AccessControlList getObjectAcl(GetObjectAclRequest getObjectAclRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setObjectAcl(String bucketName, String key, AccessControlList acl)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setObjectAcl(String bucketName, String key, CannedAccessControlList acl)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setObjectAcl(String bucketName, String key, String versionId, AccessControlList acl)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setObjectAcl(String bucketName, String key, String versionId, CannedAccessControlList acl)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setObjectAcl(SetObjectAclRequest setObjectAclRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public AccessControlList getBucketAcl(String bucketName) throws SdkClientException,
            AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketAcl(SetBucketAclRequest setBucketAclRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public AccessControlList getBucketAcl(GetBucketAclRequest getBucketAclRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketAcl(String bucketName, AccessControlList acl)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketAcl(String bucketName, CannedAccessControlList acl)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetObjectTaggingResult getObjectTagging(GetObjectTaggingRequest getObjectTaggingRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetObjectTaggingResult setObjectTagging(SetObjectTaggingRequest setObjectTaggingRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteObjectTaggingResult deleteObjectTagging(DeleteObjectTaggingRequest deleteObjectTaggingRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucket(DeleteBucketRequest deleteBucketRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucket(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public CopyObjectResult copyObject(String sourceBucketName, String sourceKey,
                                       String destinationBucketName, String destinationKey) throws SdkClientException,
            AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public CopyObjectResult copyObject(CopyObjectRequest copyObjectRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public CopyPartResult copyPart(CopyPartRequest copyPartRequest) throws SdkClientException,
            AmazonServiceException {
        throw new NotImplementedException();
    }

    @Override
    public void deleteObject(String bucketName, String key)
            throws SdkClientException, AmazonServiceException {
        deleteObject(new DeleteObjectRequest(bucketName, key));
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteObject(DeleteObjectRequest deleteObjectRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteObjectsResult deleteObjects(DeleteObjectsRequest deleteObjectsRequest) throws SdkClientException,
            AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteVersion(String bucketName, String key, String versionId)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteVersion(DeleteVersionRequest deleteVersionRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketLoggingConfiguration getBucketLoggingConfiguration(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketLoggingConfiguration getBucketLoggingConfiguration(
            GetBucketLoggingConfigurationRequest getBucketLoggingConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketLoggingConfiguration(SetBucketLoggingConfigurationRequest setBucketLoggingConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketVersioningConfiguration getBucketVersioningConfiguration(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketVersioningConfiguration getBucketVersioningConfiguration(GetBucketVersioningConfigurationRequest getBucketVersioningConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketVersioningConfiguration(SetBucketVersioningConfigurationRequest setBucketVersioningConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketLifecycleConfiguration getBucketLifecycleConfiguration(String bucketName) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketLifecycleConfiguration getBucketLifecycleConfiguration(
            GetBucketLifecycleConfigurationRequest getBucketLifecycleConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketLifecycleConfiguration(String bucketName, BucketLifecycleConfiguration bucketLifecycleConfiguration) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketLifecycleConfiguration(SetBucketLifecycleConfigurationRequest setBucketLifecycleConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketLifecycleConfiguration(String bucketName) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketLifecycleConfiguration(DeleteBucketLifecycleConfigurationRequest deleteBucketLifecycleConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketCrossOriginConfiguration getBucketCrossOriginConfiguration(String bucketName) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketCrossOriginConfiguration getBucketCrossOriginConfiguration(
            GetBucketCrossOriginConfigurationRequest getBucketCrossOriginConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketCrossOriginConfiguration(String bucketName, BucketCrossOriginConfiguration bucketCrossOriginConfiguration) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketCrossOriginConfiguration(SetBucketCrossOriginConfigurationRequest setBucketCrossOriginConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketCrossOriginConfiguration(String bucketName) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketCrossOriginConfiguration(DeleteBucketCrossOriginConfigurationRequest deleteBucketCrossOriginConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketTaggingConfiguration getBucketTaggingConfiguration(String bucketName) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketTaggingConfiguration getBucketTaggingConfiguration(
            GetBucketTaggingConfigurationRequest getBucketTaggingConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketTaggingConfiguration(String bucketName, BucketTaggingConfiguration bucketTaggingConfiguration) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketTaggingConfiguration(SetBucketTaggingConfigurationRequest setBucketTaggingConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketTaggingConfiguration(String bucketName) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketTaggingConfiguration(
            DeleteBucketTaggingConfigurationRequest deleteBucketTaggingConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketNotificationConfiguration getBucketNotificationConfiguration(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketNotificationConfiguration getBucketNotificationConfiguration(GetBucketNotificationConfigurationRequest getBucketNotificationConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketNotificationConfiguration(SetBucketNotificationConfigurationRequest setBucketNotificationConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketNotificationConfiguration(String bucketName, BucketNotificationConfiguration bucketNotificationConfiguration)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketWebsiteConfiguration getBucketWebsiteConfiguration(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketWebsiteConfiguration getBucketWebsiteConfiguration(GetBucketWebsiteConfigurationRequest getBucketWebsiteConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketWebsiteConfiguration(String bucketName, BucketWebsiteConfiguration configuration)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketWebsiteConfiguration(SetBucketWebsiteConfigurationRequest setBucketWebsiteConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketWebsiteConfiguration(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketWebsiteConfiguration(DeleteBucketWebsiteConfigurationRequest deleteBucketWebsiteConfigurationRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketPolicy getBucketPolicy(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketPolicy getBucketPolicy(GetBucketPolicyRequest getBucketPolicyRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketPolicy(String bucketName, String policyText)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketPolicy(SetBucketPolicyRequest setBucketPolicyRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketPolicy(String bucketName)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketPolicy(DeleteBucketPolicyRequest deleteBucketPolicyRequest)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public URL generatePresignedUrl(String bucketName, String key, Date expiration)
            throws SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public URL generatePresignedUrl(String bucketName, String key, Date expiration, HttpMethod method)
            throws SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public URL generatePresignedUrl(GeneratePresignedUrlRequest generatePresignedUrlRequest)
            throws SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public UploadPartResult uploadPart(UploadPartRequest request)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public PartListing listParts(ListPartsRequest request)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void abortMultipartUpload(AbortMultipartUploadRequest request)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public CompleteMultipartUploadResult completeMultipartUpload(CompleteMultipartUploadRequest request)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public MultipartUploadListing listMultipartUploads(ListMultipartUploadsRequest request)
            throws SdkClientException, AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public S3ResponseMetadata getCachedResponseMetadata(AmazonWebServiceRequest request) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public @Deprecated
    void restoreObject(RestoreObjectRequest request)
            throws AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public RestoreObjectResult restoreObjectV2(RestoreObjectRequest request)
            throws AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public @Deprecated
    void restoreObject(String bucketName, String key, int expirationInDays)
            throws AmazonServiceException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void enableRequesterPays(String bucketName)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void disableRequesterPays(String bucketName)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public boolean isRequesterPaysEnabled(String bucketName)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketReplicationConfiguration(String bucketName,
                                                  BucketReplicationConfiguration configuration)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketReplicationConfiguration(
            SetBucketReplicationConfigurationRequest setBucketReplicationConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketReplicationConfiguration getBucketReplicationConfiguration(
            String bucketName) throws AmazonServiceException,
            SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketReplicationConfiguration getBucketReplicationConfiguration(GetBucketReplicationConfigurationRequest getBucketReplicationConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketReplicationConfiguration(String bucketName)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void deleteBucketReplicationConfiguration
    (DeleteBucketReplicationConfigurationRequest request)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public boolean doesObjectExist(String bucketName, String objectName)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketAccelerateConfiguration getBucketAccelerateConfiguration(
            String bucketName) throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public BucketAccelerateConfiguration getBucketAccelerateConfiguration(
            GetBucketAccelerateConfigurationRequest getBucketAccelerateConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketAccelerateConfiguration(String bucketName,
                                                 BucketAccelerateConfiguration accelerateConfiguration)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public void setBucketAccelerateConfiguration(
            SetBucketAccelerateConfigurationRequest setBucketAccelerateConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketMetricsConfigurationResult deleteBucketMetricsConfiguration(
            String bucketName, String id) throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketMetricsConfigurationResult deleteBucketMetricsConfiguration(
            DeleteBucketMetricsConfigurationRequest deleteBucketMetricsConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketMetricsConfigurationResult getBucketMetricsConfiguration(
            String bucketName, String id) throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketMetricsConfigurationResult getBucketMetricsConfiguration(
            GetBucketMetricsConfigurationRequest getBucketMetricsConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetBucketMetricsConfigurationResult setBucketMetricsConfiguration(
            String bucketName, MetricsConfiguration metricsConfiguration)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetBucketMetricsConfigurationResult setBucketMetricsConfiguration(
            SetBucketMetricsConfigurationRequest setBucketMetricsConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public ListBucketMetricsConfigurationsResult listBucketMetricsConfigurations(
            ListBucketMetricsConfigurationsRequest listBucketMetricsConfigurationsRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketAnalyticsConfigurationResult deleteBucketAnalyticsConfiguration(
            String bucketName, String id) throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketAnalyticsConfigurationResult deleteBucketAnalyticsConfiguration(
            DeleteBucketAnalyticsConfigurationRequest deleteBucketAnalyticsConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketAnalyticsConfigurationResult getBucketAnalyticsConfiguration(
            String bucketName, String id) throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketAnalyticsConfigurationResult getBucketAnalyticsConfiguration(
            GetBucketAnalyticsConfigurationRequest getBucketAnalyticsConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetBucketAnalyticsConfigurationResult setBucketAnalyticsConfiguration(
            String bucketName, AnalyticsConfiguration analyticsConfiguration)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetBucketAnalyticsConfigurationResult setBucketAnalyticsConfiguration(
            SetBucketAnalyticsConfigurationRequest setBucketAnalyticsConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public ListBucketAnalyticsConfigurationsResult listBucketAnalyticsConfigurations(
            ListBucketAnalyticsConfigurationsRequest listBucketAnalyticsConfigurationsRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketInventoryConfigurationResult deleteBucketInventoryConfiguration(
            String bucketName, String id) throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketInventoryConfigurationResult deleteBucketInventoryConfiguration(
            DeleteBucketInventoryConfigurationRequest deleteBucketInventoryConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketInventoryConfigurationResult getBucketInventoryConfiguration(
            String bucketName, String id) throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketInventoryConfigurationResult getBucketInventoryConfiguration(
            GetBucketInventoryConfigurationRequest getBucketInventoryConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetBucketInventoryConfigurationResult setBucketInventoryConfiguration(
            String bucketName, InventoryConfiguration inventoryConfiguration)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetBucketInventoryConfigurationResult setBucketInventoryConfiguration(
            SetBucketInventoryConfigurationRequest setBucketInventoryConfigurationRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public ListBucketInventoryConfigurationsResult listBucketInventoryConfigurations(
            ListBucketInventoryConfigurationsRequest listBucketInventoryConfigurationsRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketEncryptionResult deleteBucketEncryption(String bucketName)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeleteBucketEncryptionResult deleteBucketEncryption(DeleteBucketEncryptionRequest request)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketEncryptionResult getBucketEncryption(String bucketName)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketEncryptionResult getBucketEncryption(GetBucketEncryptionRequest request)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetBucketEncryptionResult setBucketEncryption(SetBucketEncryptionRequest setBucketEncryptionRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetPublicAccessBlockResult setPublicAccessBlock(SetPublicAccessBlockRequest request) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetPublicAccessBlockResult getPublicAccessBlock(GetPublicAccessBlockRequest request) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public DeletePublicAccessBlockResult deletePublicAccessBlock(DeletePublicAccessBlockRequest request) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetBucketPolicyStatusResult getBucketPolicyStatus(GetBucketPolicyStatusRequest request) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SelectObjectContentResult selectObjectContent(SelectObjectContentRequest selectRequest)
            throws AmazonServiceException, SdkClientException {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetObjectLegalHoldResult setObjectLegalHold(SetObjectLegalHoldRequest setObjectLegalHoldRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetObjectLegalHoldResult getObjectLegalHold(GetObjectLegalHoldRequest getObjectLegalHoldRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetObjectLockConfigurationResult setObjectLockConfiguration(SetObjectLockConfigurationRequest setObjectLockConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetObjectLockConfigurationResult getObjectLockConfiguration(GetObjectLockConfigurationRequest getObjectLockConfigurationRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public SetObjectRetentionResult setObjectRetention(SetObjectRetentionRequest setObjectRetentionRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public GetObjectRetentionResult getObjectRetention(GetObjectRetentionRequest getObjectRetentionRequest) {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public Region getRegion() {
        throw new NotImplementedException();
    }

    /**
     * Not implemented
     */
    @Override
    public String getRegionName() {
        throw new NotImplementedException();
    }

    //endregion
}
