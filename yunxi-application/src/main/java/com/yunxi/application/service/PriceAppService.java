package com.yunxi.application.service;

import com.yunxi.application.dto.PriceRow;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.domain.price.ClothesCategory;
import com.yunxi.domain.price.ClothesPrice;
import com.yunxi.domain.price.PricePolicy;
import com.yunxi.domain.price.PriceRepository;
import com.yunxi.domain.price.WashType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 价目表应用服务 —— 分类 / 洗涤方式 / 价格三张读接口（写接口见 savePrices）。
 */
@Service
public class PriceAppService {

    private final PriceRepository priceRepository;

    /** 构造注入（替代 @Autowired） */
    public PriceAppService(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    // ──────────────── 写 ────────────────

    /**
     * 给某个二级分类设置价格（店长/管理员）。
     *
     * 规则（设计文档 §4.5）：
     *   1. 只能给**叶子**分类定价 —— "上衣"本身不洗，给它定价没有意义
     *   2. 传普洗：写普洗；**价格 > 0 时同事务派生**精洗 = 普洗 + 20
     *   3. 传精洗：该分类已有可用普洗 → 拒绝（精洗是算出来的，不是填出来的）；
     *      没有普洗 → 允许手填（羽绒服 60.00 就靠这条"逃生舱"）
     *   4. 传单熨：独立定价，直接写
     *
     * @param categoryId 目标分类（以路径参数为准，忽略明细里自带的 categoryId）
     * @param prices     要写入的价格（只用到 washTypeId 与 price）
     */
    @Transactional
    public Result<Void> savePrices(Long categoryId, List<ClothesPrice> prices) {
        if (prices == null || prices.isEmpty()) {
            throw new BusinessException("至少要传一条价格");
        }
        ClothesCategory category = priceRepository.findCategoryById(categoryId)
                .orElseThrow(() -> new BusinessException(400,
                        "衣物分类不存在: " + categoryId));
        if (!category.isLeaf()) {
            throw new BusinessException("只能给二级（叶子）分类设置价格");
        }

        // ── 第一遍：只看不写。把"这批请求是否合法"在一处判完，
        //    避免写了一半点才发现有问题（虽然事务能回滚，但能不动手就不动手）
        BigDecimal requestedPlain = null;       // 本次请求里的普洗价
        boolean refinedRequested = false;
        for (ClothesPrice p : prices) {
            PricePolicy.requireKnownWashType(p.getWashTypeId());
            if (p.getPrice() == null || p.getPrice().signum() < 0) {
                throw new BusinessException("价格不能为空或负数");
            }
            if (p.getWashTypeId() == PricePolicy.PLAIN) {
                requestedPlain = p.getPrice();
            }
            if (p.getWashTypeId() == PricePolicy.REFINED) {
                refinedRequested = true;
            }
        }

        // 判断"这次请求执行完之后，该分类有没有可用的普洗"。
        // 本次传了普洗就以本次为准 —— 一次请求里同时改普洗和精洗时，
        // 客户端的意思显然是"按新普洗价算"，不是"按库里的旧价算"。
        BigDecimal effectivePlain = requestedPlain != null
                ? requestedPlain
                : existingPlainPrice(categoryId);
        boolean hasSupportedPlain =
                effectivePlain != null && effectivePlain.signum() > 0;

        if (refinedRequested && hasSupportedPlain) {
            throw new BusinessException(
                    "精洗价格由普洗价自动计算（普洗价+20），不能手工设置");
        }

        // ── 第二遍：校验全过，落库
        for (ClothesPrice p : prices) {
            priceRepository.savePrice(categoryId, p.getWashTypeId(), p.getPrice());
            if (p.getWashTypeId() == PricePolicy.PLAIN && p.getPrice().signum() > 0) {
                priceRepository.savePrice(categoryId, PricePolicy.REFINED,
                        PricePolicy.deriveRefined(p.getPrice()));
            }
        }
        return Result.ok(null);
    }

    /**
     * 该分类当前的普洗价（没有行或价格为 0 时返回 0/ null）。
     *
     * 关掉普洗（传 0）**不会**连带清掉已算出的精洗价：那笔精洗价从此转成
     * 手填价（逃生舱语义），静默清零等于替人删数据 —— 想清就显式传一条精洗价。
     */
    private BigDecimal existingPlainPrice(Long categoryId) {
        return priceRepository.findPricesByCategoryIds(List.of(categoryId))
                .getOrDefault(categoryId, List.of()).stream()
                .filter(p -> p.getWashTypeId() == PricePolicy.PLAIN)
                .map(ClothesPrice::getPrice)
                .findFirst()
                .orElse(null);
    }

    /** 全部分类（一级 + 叶子），前端按 parentId 组树 */
    public Result<List<ClothesCategory>> listCategories() {
        return Result.ok(priceRepository.findAllCategories());
    }

    /** 洗涤方式（固定 3 种） */
    public Result<List<WashType>> listWashTypes() {
        return Result.ok(priceRepository.findAllWashTypes());
    }

    /**
     * 价目表（已拼好分类名与洗涤方式名）。
     *
     * 三条 SQL 各查一张表，在内存里按 id 拼——而不是写一条 JOIN：
     * 表都是几十行，三次往返换来的是"这三张表谁都不依赖谁"，
     * 将来要单独缓存某一张也不用拆 SQL。
     */
    public Result<List<PriceRow>> listPrices() {
        Map<Long, ClothesCategory> categoryById = priceRepository.findAllCategories()
                .stream()
                .collect(Collectors.toMap(ClothesCategory::getId, Function.identity()));
        Map<Long, WashType> washTypeById = priceRepository.findAllWashTypes()
                .stream()
                .collect(Collectors.toMap(WashType::getId, Function.identity()));

        List<PriceRow> rows = priceRepository.findAllPrices().stream()
                // 分类或洗涤方式被删掉后残留的价格行：查不到名字就跳过。
                // 展示型读接口不为此报错——脏数据不该让整个价目表打不开
                .filter(p -> categoryById.containsKey(p.getCategoryId())
                        && washTypeById.containsKey(p.getWashTypeId()))
                .map(p -> {
                    ClothesCategory category = categoryById.get(p.getCategoryId());
                    return new PriceRow(
                            p.getCategoryId(),
                            category.getName(),
                            category.getParentId(),
                            p.getWashTypeId(),
                            washTypeById.get(p.getWashTypeId()).getName(),
                            p.getPrice(),
                            p.isSupported());
                })
                .toList();
        return Result.ok(rows);
    }
}
