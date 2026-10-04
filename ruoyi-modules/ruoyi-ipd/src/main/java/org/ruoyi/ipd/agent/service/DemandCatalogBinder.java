package org.ruoyi.ipd.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分拣运行成功后回写需求单。
 *
 * <p>优先使用同一次目录查询对需求原文做唯一整词匹配得到的编码。
 * 全文「产品线：编码」「产品：编码」只在对应字段为空时兼容。对不上就不写，不覆盖已有值。
 */
public class DemandCatalogBinder {

    private static final Logger log = LoggerFactory.getLogger(DemandCatalogBinder.class);
    private static final Pattern LINE_MARK = Pattern.compile("(?m)^产品线[：:]\\s*(\\S+)\\s*$");
    private static final Pattern PRODUCT_MARK = Pattern.compile("(?m)^产品[：:]\\s*(\\S+)\\s*$");
    private static final String UNKNOWN = "未取得";
    private static final String UNSPECIFIED = "unspecified";

    private final RequirementMapper requirementMapper;
    private final ProductLineMapper lineMapper;
    private final ProductMapper productMapper;

    /**
     * 同一次目录查询得到的可写回编码。对不上时字段为 null，不用空串。
     *
     * @param lineCode 产品线编码
     * @param productCode 产品编码
     */
    public record CatalogHit(String lineCode, String productCode) {
    }

    /**
     * 提示摘录和结构化编码，来自同一次查询。
     *
     * @param appendix 提示摘录
     * @param hit 结构化编码
     */
    public record BindContext(String appendix, CatalogHit hit) {
    }

    /**
     * @param requirementMapper 需求单
     * @param lineMapper 产品线
     * @param productMapper 产品
     */
    public DemandCatalogBinder(RequirementMapper requirementMapper, ProductLineMapper lineMapper,
                               ProductMapper productMapper) {
        this.requirementMapper = requirementMapper;
        this.lineMapper = lineMapper;
        this.productMapper = productMapper;
    }

    /**
     * 用助手全文里的标记回写。结构化编码为空。
     *
     * @param requirementId 需求单 ID
     * @param answer 助手全文，可空
     */
    public void apply(Long requirementId, String answer) {
        throw missingTenant();
    }

    /**
     * 优先用结构化编码回写。该字段非空但对不上唯一在册行时，不回落到全文正则。
     *
     * @param requirementId 需求单 ID
     * @param answer 助手全文，可空
     * @param hit 运行开始时的目录匹配，可空
     */
    public void apply(Long requirementId, String answer, CatalogHit hit) {
        throw missingTenant();
    }

    public void apply(Long requirementId, String answer, CatalogHit hit, String tenantId) {
        tenantId = requiredTenant(tenantId);
        if (requirementId == null) {
            return;
        }
        String lineCode = codeFor(hit == null ? null : hit.lineCode(), LINE_MARK, answer);
        String productCode = codeFor(hit == null ? null : hit.productCode(), PRODUCT_MARK, answer);
        if (lineCode == null && productCode == null) {
            return;
        }
        Requirement requirement = requirementMapper.selectOne(new LambdaQueryWrapper<Requirement>()
            .eq(Requirement::getId, requirementId).apply("tenant_id = {0}", tenantId));
        if (requirement == null) {
            return;
        }
        ProductLine line = uniqueLine(lineCode, tenantId);
        Product product = uniqueProduct(productCode, tenantId);
        if (line != null && product != null && product.getProductLineId() != null
            && !product.getProductLineId().equals(line.getId())) {
            return;
        }
        if (line == null && product != null && product.getProductLineId() != null) {
            line = lineMapper.selectOne(new LambdaQueryWrapper<ProductLine>()
                .eq(ProductLine::getId, product.getProductLineId()).eq(ProductLine::getStatus, "ACTIVE")
                .eq(ProductLine::getTenantId, tenantId));
            if (line == null || !"ACTIVE".equals(line.getStatus()) || UNSPECIFIED.equals(line.getLineCode())) {
                line = null;
            }
        }
        Long originalLineId = requirement.getProductLineId();
        Long originalProductId = requirement.getProductId();
        boolean changed = false;
        if (line != null && requirement.getProductLineId() == null) {
            requirement.setProductLineId(line.getId());
            changed = true;
        }
        if (product != null && requirement.getProductId() == null
            && product.getProductLineId() != null
            && product.getProductLineId().equals(requirement.getProductLineId())) {
            requirement.setProductId(product.getId());
            changed = true;
        }
        if (changed) {
            LambdaUpdateWrapper<Requirement> update = new LambdaUpdateWrapper<Requirement>()
                .eq(Requirement::getId, requirementId).apply("tenant_id = {0}", tenantId);
            if (originalLineId == null) update.isNull(Requirement::getProductLineId);
            else update.eq(Requirement::getProductLineId, originalLineId);
            if (originalProductId == null) update.isNull(Requirement::getProductId);
            else update.eq(Requirement::getProductId, originalProductId);
            if (originalLineId == null && requirement.getProductLineId() != null)
                update.set(Requirement::getProductLineId, requirement.getProductLineId());
            if (originalProductId == null && requirement.getProductId() != null)
                update.set(Requirement::getProductId, requirement.getProductId());
            if (requirementMapper.update(null, update) != 1) {
                throw new IllegalStateException("需求目录绑定发生冲突，请重新读取需求后重试");
            }
            log.info("demand_bind requirementId={} lineSet={} productSet={}",
                requirementId, requirement.getProductLineId() != null, requirement.getProductId() != null);
        }
    }

    /**
     * 一次查出需求原文、可写回产品线和产品，并做唯一整词匹配。
     *
     * @param requirementId 需求单，可空
     * @return 提示摘录和结构化编码
     */
    public BindContext open(Long requirementId) {
        throw missingTenant();
    }

    public BindContext open(Long requirementId, String tenantId) {
        tenantId = requiredTenant(tenantId);
        StringBuilder sb = new StringBuilder();
        Requirement requirement = null;
        if (requirementId != null) {
            requirement = requirementMapper.selectOne(new LambdaQueryWrapper<Requirement>()
            .eq(Requirement::getId, requirementId).apply("tenant_id = {0}", tenantId));
            if (requirement == null) throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.NOT_FOUND, "需求不在当前租户范围内");
            if (requirement != null) {
                appendFact(sb, "本张需求标题：", requirement.getTitle());
                appendFact(sb, "本张需求内容：", requirement.getContent());
            }
        }
        List<ProductLine> lineRows = lineMapper.selectList(new LambdaQueryWrapper<ProductLine>()
            .eq(ProductLine::getStatus, "ACTIVE").eq(ProductLine::getTenantId, tenantId)
            .orderByAsc(ProductLine::getId));
        java.util.LinkedHashMap<Long, String> lineCodes = new java.util.LinkedHashMap<>();
        List<String> listedLines = new ArrayList<>();
        sb.append("可写回的产品线，每行「编码 名称」：\n");
        if (lineRows != null) {
            for (ProductLine line : lineRows) {
                if (line.getId() == null || line.getLineCode() == null || line.getLineCode().isBlank()
                    || UNSPECIFIED.equals(line.getLineCode())) {
                    continue;
                }
                String code = line.getLineCode().trim();
                lineCodes.put(line.getId(), code);
                listedLines.add(code);
                if (sb.length() < 8000) sb.append(code).append(' ')
                    .append(line.getLineName() == null ? "" : shortName(line.getLineName())).append('\n');
            }
        }
        // 所有候选限定在上面有效目录线；完整匹配不受提示摘录上限影响。
        List<Product> productRows = lineCodes.isEmpty() ? List.of()
            : productMapper.selectList(new LambdaQueryWrapper<Product>()
                .in(Product::getProductLineId, lineCodes.keySet()).eq(Product::getTenantId, tenantId).orderByAsc(Product::getId));
        String haystack = requirement == null ? "" : haystack(requirement);
        List<Product> matches = productRows == null ? List.of() : productRows.stream()
            .filter(p -> lineCodes.containsKey(p.getProductLineId()))
            .filter(p -> (usable(p.getProductCode()) != null && containsToken(haystack, p.getProductCode().trim()))
                || (usable(p.getModelCode()) != null && containsToken(haystack, p.getModelCode().trim())))
            .toList();
        String matchedProduct = matches.size() == 1
            ? firstCode(matches.get(0).getProductCode(), matches.get(0).getModelCode()) : null;
        int shown = 0;
        List<Product> promptProducts = new ArrayList<>(matches);
        if (productRows != null) for (Product p : productRows) if (!matches.contains(p)) promptProducts.add(p);
        sb.append("可写回的产品，每行「编码 名称 产品线编码」：\n");
        if (productRows != null) {
            for (Product product : promptProducts) {
                String code = firstCode(product.getProductCode(), product.getModelCode());
                String lineCode = product.getProductLineId() == null
                    ? null : lineCodes.get(product.getProductLineId());
                if (code == null || lineCode == null) {
                    continue;
                }
                if (shown >= 120 || sb.length() >= 16000) continue;
                shown++;
                sb.append(code).append(' ')
                    .append(product.getProductName() == null ? "" : shortName(product.getProductName()))
                    .append(' ').append(lineCode).append('\n');
            }
        }
        if (productRows != null && shown < productRows.size()) {
            sb.append("目录摘录已限长，完整目录唯一匹配已执行；未取得唯一依据时不要猜测。\n");
        }
        return new BindContext(sb.toString(), new CatalogHit(
            uniqueListedCode(haystack, listedLines), matchedProduct));
    }

    /**
     * 给分拣运行附上这张需求的原文，以及回写器能对上的目录编码。
     *
     * @param requirementId 需求单，可空
     * @return 提示摘录
     */
    public String promptAppendix(Long requirementId) {
        throw missingTenant();
    }

    public String promptAppendix(Long requirementId, String tenantId) {
        return open(requirementId, tenantId).appendix();
    }

    private static org.ruoyi.ipd.common.IpdBusinessException missingTenant() {
        return new org.ruoyi.ipd.common.IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.PARAM_INVALID,
            "需求目录必须指定可信租户范围");
    }

    private static String requiredTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw missingTenant();
        return tenantId;
    }

    private static String codeFor(String structured, Pattern mark, String answer) {
        String chosen = usable(structured);
        if (chosen != null) {
            return chosen;
        }
        if (answer == null || answer.isBlank()) {
            return null;
        }
        return singleCode(mark, answer);
    }

    private static String usable(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty() || UNKNOWN.equals(trimmed) || UNSPECIFIED.equals(trimmed) || trimmed.length() > 64) {
            return null;
        }
        return trimmed;
    }

    private static String haystack(Requirement requirement) {
        return String.join("\n",
            requirement.getTitle() == null ? "" : requirement.getTitle(),
            requirement.getContent() == null ? "" : requirement.getContent(),
            requirement.getRawModel() == null ? "" : requirement.getRawModel());
    }

    /**
     * 正文里恰好出现一个目录编码时返回它。零个或多个不同编码返回 null。
     *
     * @param haystack 标题、内容和原始型号
     * @param codes 本次目录查询列出的编码
     * @return 唯一编码
     */
    static String uniqueListedCode(String haystack, List<String> codes) {
        if (haystack == null || haystack.isBlank() || codes == null || codes.isEmpty()) {
            return null;
        }
        String found = null;
        for (String code : codes) {
            String usable = usable(code);
            if (usable == null || !containsToken(haystack, usable)) {
                continue;
            }
            if (found != null && !found.equals(usable)) {
                return null;
            }
            found = usable;
        }
        return found;
    }

    private static boolean containsToken(String haystack, String code) {
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(code) + "(?![A-Za-z0-9_-])");
        return pattern.matcher(haystack).find();
    }

    private static void appendFact(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String text = oneLine(value);
        if (text.length() > 400) {
            text = text.substring(0, 400);
        }
        sb.append(label).append(text).append('\n');
    }

    private static String firstCode(String productCode, String modelCode) {
        if (productCode != null && !productCode.isBlank()) {
            return productCode.trim();
        }
        if (modelCode != null && !modelCode.isBlank()) {
            return modelCode.trim();
        }
        return null;
    }

    private static String shortName(String value) {
        String name = oneLine(value);
        return name.length() <= 256 ? name : name.substring(0, 256);
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * 同一标记只出现一个有效编码时返回它。空、未取得、或多个不同编码都返回 null。
     *
     * @param pattern 行首标记
     * @param answer 助手全文
     * @return 唯一编码；不能唯一确定时为 null
     */
    static String singleCode(Pattern pattern, String answer) {
        Matcher matcher = pattern.matcher(answer);
        Set<String> codes = new LinkedHashSet<>();
        while (matcher.find()) {
            String code = matcher.group(1).trim();
            if (!code.isEmpty() && !UNKNOWN.equals(code) && code.length() <= 64) {
                codes.add(code);
            }
        }
        return codes.size() == 1 ? codes.iterator().next() : null;
    }

    private ProductLine uniqueLine(String code, String tenantId) {
        if (code == null) {
            return null;
        }
        List<ProductLine> rows = lineMapper.selectList(new LambdaQueryWrapper<ProductLine>()
            .eq(ProductLine::getLineCode, code).eq(ProductLine::getTenantId, tenantId)
            .eq(ProductLine::getStatus, "ACTIVE"));
        if (rows == null || rows.size() != 1) {
            return null;
        }
        ProductLine line = rows.get(0);
        return UNSPECIFIED.equals(line.getLineCode()) ? null : line;
    }

    private Product uniqueProduct(String code, String tenantId) {
        if (code == null) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();
        Product chosen = null;
        for (Product row : listByCode(code, tenantId)) {
            if (row.getId() == null || !ids.add(row.getId())) {
                continue;
            }
            chosen = row;
        }
        return ids.size() == 1 ? chosen : null;
    }

    private List<Product> listByCode(String code, String tenantId) {
        List<Product> byCode = productMapper.selectList(new LambdaQueryWrapper<Product>()
            .eq(Product::getProductCode, code).eq(Product::getTenantId, tenantId));
        List<Product> byModel = productMapper.selectList(new LambdaQueryWrapper<Product>()
            .eq(Product::getModelCode, code).eq(Product::getTenantId, tenantId));
        ArrayList<Product> merged = new ArrayList<>();
        if (byCode != null) {
            merged.addAll(byCode);
        }
        if (byModel != null) {
            merged.addAll(byModel);
        }
        return merged;
    }
}
