package org.ruoyi.system.service;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.system.domain.bo.SysOssBo;
import org.ruoyi.system.domain.vo.SysOssUploadVo;
import org.ruoyi.system.domain.vo.SysOssVo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * 文件上传 服务层
 *
 * @author Lion Li
 */
public interface ISysOssService {

    /**
     * 查询OSS对象存储列表
     *
     * @param sysOss    OSS对象存储分页查询对象
     * @param pageQuery 分页查询实体类
     * @return 结果
     */
    TableDataInfo<SysOssVo> queryPageList(SysOssBo sysOss, PageQuery pageQuery);

    /**
     * 根据一组 ossIds 获取对应的 SysOssVo 列表
     *
     * @param ossIds 一组文件在数据库中的唯一标识集合
     * @return 包含 SysOssVo 对象的列表
     */
    List<SysOssVo> listByIds(Collection<Long> ossIds);

    /**
     * 根据 ossId 从缓存或数据库中获取 SysOssVo 对象
     *
     * @param ossId 文件在数据库中的唯一标识
     * @return SysOssVo 对象，包含文件信息
     */
    SysOssVo getById(Long ossId);

    /** 保密需求附件仅内部已验证对象权限的服务调用，禁止输出 URL。 */
    SysOssVo getPrivateById(Long ossId, String configKey);

    /**
     * 上传 MultipartFile 到对象存储服务，并保存文件信息到数据库
     *
     * @param file 要上传的 MultipartFile 对象
     * @return 上传成功后的 SysOssVo 对象，包含文件信息
     */
    SysOssVo upload(MultipartFile file);

    /** 指定专用私有桶上传，禁止回落默认存储。 */
    SysOssVo uploadPrivate(MultipartFile file, String configKey);

    /** 回滚时不依赖可能已回滚的 sys_oss 行，直接删除刚上传的存储对象。 */
    void cleanupUploadedObject(Long ossId, String configKey, String objectKey);

    /** 私有附件下载前再次确认存储没有公开权限。 */
    void downloadPrivate(Long ossId, String configKey, HttpServletResponse response) throws IOException;

    /**
     * 上传文件到对象存储服务，并保存文件信息到数据库
     *
     * @param file 要上传的文件对象
     * @return 上传成功后的 SysOssVo 对象，包含文件信息
     */
    SysOssVo upload(File file);

    /**
     * 上传文件到千问平台（千问百炼版本）
     *
     * @param file 要上传的 MultipartFile 对象
     * @return 上传成功后的 SysOssVo 对象，包含文件信息
     */
    SysOssUploadVo fileUpload(MultipartFile file);

    /**
     * 文件下载方法，支持一次性下载完整文件
     *
     * @param ossId    OSS对象ID
     * @param response HttpServletResponse对象，用于设置响应头和向客户端发送文件内容
     */
    void download(Long ossId, HttpServletResponse response) throws IOException;

    /**
     * 删除OSS对象存储
     *
     * @param ids     OSS对象ID串
     * @param isValid 判断是否需要校验
     * @return 结果
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

}
