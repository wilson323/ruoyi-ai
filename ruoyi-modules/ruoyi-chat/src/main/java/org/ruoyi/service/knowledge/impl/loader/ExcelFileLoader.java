package org.ruoyi.service.knowledge.impl.loader;

import cn.hutool.core.exceptions.UtilException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.exception.TikaException;
import org.xml.sax.SAXException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.service.knowledge.ResourceLoader;
import org.ruoyi.service.knowledge.DocumentSplitConfig;
import org.ruoyi.service.knowledge.TextSplitter;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
@AllArgsConstructor
@Slf4j
public class ExcelFileLoader implements ResourceLoader {
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private final TextSplitter textSplitter;

    @Override
    public String getContent(InputStream inputStream) {
        // 使用带缓冲的输入流包装（保持原流不自动关闭）
        try (InputStream bufferedStream = new BufferedInputStream(inputStream, DEFAULT_BUFFER_SIZE)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(bufferedStream, handler, new Metadata(), new ParseContext());
            return handler.toString();
        } catch (IOException | TikaException | SAXException e) {
            String errorMsg = "Excel文件流读取失败";
            throw new UtilException(errorMsg, e);
        } catch (RuntimeException e) {
            String errorMsg = "Excel内容解析异常";
            throw new UtilException(errorMsg, e);
        }
    }

    @Override
    public List<String> getChunkList(String content, DocumentSplitConfig config) {
        return textSplitter.split(content, config);
    }
}
