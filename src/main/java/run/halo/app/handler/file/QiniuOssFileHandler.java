package run.halo.app.handler.file;

import static run.halo.app.model.support.HaloConst.FILE_SEPARATOR;
import static run.halo.app.model.support.HaloConst.TEMP_DIR;
import static run.halo.app.model.support.HaloConst.URL_SEPARATOR;
import static run.halo.app.utils.HaloUtils.ensureSuffix;

import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.persistent.FileRecorder;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;
import run.halo.app.exception.FileOperationException;
import run.halo.app.model.enums.AttachmentType;
import run.halo.app.model.properties.AttachmentProperties;
import run.halo.app.model.properties.QiniuOssProperties;
import run.halo.app.model.support.UploadResult;
import run.halo.app.repository.AttachmentRepository;
import run.halo.app.service.OptionService;
import run.halo.app.utils.ImageUtils;
import run.halo.app.utils.JsonUtils;

/**
 * Qiniu oss file handler.
 *
 * @author johnniang
 * @author ryanwang
 * @author guqing
 * @date 2019-03-27
 */
@Slf4j
@Component
public class QiniuOssFileHandler implements FileHandler {

    private final OptionService optionService;
    private final AttachmentRepository attachmentRepository;

    public QiniuOssFileHandler(OptionService optionService,
                               AttachmentRepository attachmentRepository) {
        this.optionService = optionService;
        this.attachmentRepository = attachmentRepository;
    }

    @Override
    public UploadResult upload(MultipartFile file) {
        Assert.notNull(file, "Multipart file must not be null");

        Region region = optionService.getQiniuRegion();

        boolean ifRemoveEXIF = (boolean) optionService
            .getByPropertyOfNonNull(AttachmentProperties.IMAGE_EXIF_REMOVE_ENABLE);
        String accessKey =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_ACCESS_KEY).toString();
        String secretKey =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_SECRET_KEY).toString();
        String bucket =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_BUCKET).toString();
        String protocol =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_PROTOCOL).toString();
        String domain =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_DOMAIN).toString();
        String source =
            optionService.getByPropertyOrDefault(QiniuOssProperties.OSS_SOURCE, String.class, "");
        String styleRule = optionService
            .getByPropertyOrDefault(QiniuOssProperties.OSS_STYLE_RULE, String.class, "");
        String thumbnailStyleRule = optionService
            .getByPropertyOrDefault(QiniuOssProperties.OSS_THUMBNAIL_STYLE_RULE, String.class, "");

        // Create configuration
        Configuration configuration = new Configuration(region);

        // Create auth
        Auth auth = Auth.create(accessKey, secretKey);
        // Build put plicy
        StringMap putPolicy = new StringMap();
        putPolicy.put("returnBody",
            "{\"key\":\"$(key)\",\"hash\":\"$(etag)\",\"size\":$(fsize),\"width\":$(imageInfo"
                + ".width),\"height\":$(imageInfo.height)}");
        // Get upload token
        String uploadToken = auth.uploadToken(bucket, null, 60 * 60, putPolicy);

        // Create temp path
        Path tmpPath = Paths.get(ensureSuffix(TEMP_DIR, FILE_SEPARATOR), bucket);

        StringBuilder basePath = new StringBuilder(protocol)
            .append(domain)
            .append(URL_SEPARATOR);

        try {
            FilePathDescriptor pathDescriptor = new FilePathDescriptor.Builder()
                .setBasePath(basePath.toString())
                .setSubPath(source)
                .setAutomaticRename(true)
                .setRenamePredicate(relativePath ->
                    attachmentRepository
                        .countByFileKeyAndType(relativePath, AttachmentType.QINIUOSS) > 0)
                .setOriginalName(file.getOriginalFilename())
                .build();

            // Get file recorder for temp directory
            FileRecorder fileRecorder = new FileRecorder(tmpPath.toFile());
            // Get upload manager
            UploadManager uploadManager = new UploadManager(configuration, fileRecorder);
            // Put the file
            Response response;
            if (ifRemoveEXIF) {
                //Get image without EXIF information if it is required
                File withoutEXIF = removeEXIF(file);
                if (withoutEXIF != null) {
                    response = uploadManager
                        .put(Files.newInputStream(withoutEXIF.toPath()),
                            pathDescriptor.getRelativePath(),
                            uploadToken, null, null);
                    Files.delete(withoutEXIF.toPath());
                } else {
                    log.warn("Remove EXIF failed, upload file with EXIF");
                    response = uploadManager
                        .put(file.getInputStream(), pathDescriptor.getRelativePath(), uploadToken,
                            null,
                            null);
                }
            } else {
                response = uploadManager
                    .put(file.getInputStream(), pathDescriptor.getRelativePath(), uploadToken, null,
                        null);
            }

            if (log.isDebugEnabled()) {
                log.debug("Qiniu oss response: [{}]", response.toString());
                log.debug("Qiniu oss response body: [{}]", response.bodyString());
            }

            // Convert response
            PutSet putSet = JsonUtils.jsonToObject(response.bodyString(), PutSet.class);

            // Get file full path
            String fullPath = pathDescriptor.getFullPath();

            // Build upload result
            UploadResult result = new UploadResult();
            result.setFilename(pathDescriptor.getName());

            result.setFilePath(StringUtils.isBlank(styleRule) ? fullPath : fullPath + styleRule);
            result.setKey(pathDescriptor.getRelativePath());
            result.setSuffix(pathDescriptor.getExtension());
            result.setWidth(putSet.getWidth());
            result.setHeight(putSet.getHeight());
            result.setMediaType(MediaType.valueOf(Objects.requireNonNull(file.getContentType())));
            result.setSize(file.getSize());

            if (isImageType(file)) {
                if (ImageUtils.EXTENSION_ICO.equals(pathDescriptor.getExtension())) {
                    result.setThumbPath(fullPath);
                } else {
                    result.setThumbPath(StringUtils.isBlank(thumbnailStyleRule) ? fullPath :
                        fullPath + thumbnailStyleRule);
                }
            }

            return result;
        } catch (IOException e) {
            if (e instanceof QiniuException) {
                log.error("Qiniu oss error response: [{}]", ((QiniuException) e).response);
            }

            throw new FileOperationException("上传附件 " + file.getOriginalFilename() + " 到七牛云失败", e);
        }
    }

    @Override
    public void delete(String key) {
        Assert.notNull(key, "File key must not be blank");

        Region region = optionService.getQiniuRegion();

        String accessKey =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_ACCESS_KEY).toString();
        String secretKey =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_SECRET_KEY).toString();
        String bucket =
            optionService.getByPropertyOfNonNull(QiniuOssProperties.OSS_BUCKET).toString();

        // Create configuration
        Configuration configuration = new Configuration(region);

        // Create auth
        Auth auth = Auth.create(accessKey, secretKey);

        BucketManager bucketManager = new BucketManager(auth, configuration);

        try {
            Response response = bucketManager.delete(bucket, key);
            if (!response.isOK()) {
                log.warn("附件 " + key + " 从七牛云删除失败");
                throw new FileOperationException("附件 " + key + " 从七牛云删除失败");
            }
        } catch (QiniuException e) {
            log.error("Qiniu oss error response: [{}]", e.response);
            throw new FileOperationException("附件 " + key + " 从七牛云删除失败", e);
        }
    }

    @Override
    public AttachmentType getAttachmentType() {
        return AttachmentType.QINIUOSS;
    }

    @Data
    @NoArgsConstructor
    private static class PutSet {

        public String hash;

        public String key;

        private Long size;

        private Integer width;

        private Integer height;
    }
}

