package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.CategoryView;
import com.yunxi.application.dto.PriceItem;
import com.yunxi.application.dto.PriceRow;
import com.yunxi.application.dto.WashTypeView;
import com.yunxi.application.service.PriceAppService;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.interfaces.dto.PriceItemRequest;
import com.yunxi.interfaces.dto.SavePricesRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 价目表接口 —— 分类 / 洗涤方式 / 价格。
 *
 * 鉴权说明：
 *   - **读**（三个 GET）：任意有效 token 即可。价目表是顾客下单必须看到的公开信息，
 *     顾客看不到价格就没法下单；拦在 token 这一层（/api/** 由 JwtInterceptor
 *     统一把守，只有 /api/auth/** 例外）足以挡住公网匿名访问
 *   - **写**（PUT）：只认**店长**（2026-09-11 起）。管理员不能改价 ——
 *     这道闸门在 JwtInterceptor 的 checkRoleGate 里，这里只挡顾客
 */
@RestController
@RequestMapping("/api")
public class PriceController {

    private final PriceAppService priceAppService;

    public PriceController(PriceAppService priceAppService) {
        this.priceAppService = priceAppService;
    }

    /** 衣物分类（含一级与叶子，前端按 parentId 组树） */
    @GetMapping("/categories")
    public Result<List<CategoryView>> listCategories() {
        return priceAppService.listCategories();
    }

    /** 洗涤方式（固定 3 种） */
    @GetMapping("/wash-types")
    public Result<List<WashTypeView>> listWashTypes() {
        return priceAppService.listWashTypes();
    }

    /** 价目表（已拼好分类名/洗涤方式名，前端直接渲染） */
    @GetMapping("/prices")
    public Result<List<PriceRow>> listPrices() {
        return priceAppService.listPrices();
    }

    /**
     * 设置某个二级分类的价格（仅店长 —— 管理员由 JwtInterceptor 的闸门挡在外面）。
     * 例：PUT /api/prices/11  body {"prices":[{"washTypeId":1,"price":20.00}]}
     * 改普洗价会自动把该分类的精洗价重算成 普洗+20。
     */
    @PutMapping("/prices/{categoryId}")
    public Result<Void> savePrices(@PathVariable Long categoryId,
                                   @RequestBody SavePricesRequest request,
                                   HttpServletRequest http) {
        // 这里只剩"顾客不能定价"这一条要挡：价格是门店定的，不是顾客定的。
        // "管理员也不能定价"在拦截器的角色闸门里（按 URL 收口），不在这
        if (!"staff".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用员工账号操作");
        }
        if (request.prices() == null || request.prices().isEmpty()) {
            return Result.fail(400, "至少要传一条价格");
        }
        // ── 先把形状问题拦掉（null 字段要 400 而不是 NPE 变 500）──
        // 转成应用层的 PriceItem，而不是 domain 的 ClothesPrice：接口层不碰 domain（§3.1）
        List<PriceItem> prices = new ArrayList<>();
        for (int i = 0; i < request.prices().size(); i++) {
            PriceItemRequest it = request.prices().get(i);
            if (it == null || it.washTypeId() == null || it.price() == null) {
                return Result.fail(400, "第 " + (i + 1) + " 条缺少洗涤方式或价格");
            }
            if (it.price().signum() < 0) {
                return Result.fail(400, "第 " + (i + 1) + " 条价格不能为负数");
            }
            prices.add(new PriceItem(it.washTypeId(), it.price()));
        }
        // 洗涤方式是否合法、叶子校验、精洗派生规则，都在应用服务里（可脱离 web 测试）
        return priceAppService.savePrices(categoryId, prices);
    }
}
