package org.example.dormrepairsystem.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.dormrepairsystem.dto.OrderWithUserDTO;
import org.example.dormrepairsystem.entity.Dormitory;
import org.example.dormrepairsystem.entity.OrderImage;
import org.example.dormrepairsystem.entity.RepairOrder;
import org.example.dormrepairsystem.service.DormitoryService;
import org.example.dormrepairsystem.service.OrderImageService;
import org.example.dormrepairsystem.service.RepairOrderService;
import org.example.dormrepairsystem.util.AuthUtil;
import org.example.dormrepairsystem.util.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/repair-orders")
@Slf4j
@Tag(name = "维修订单管理", description = "维修订单相关接口")
public class RepairOrderController {

    /** 合法订单状态 */
    private static final Set<String> VALID_STATUSES = Set.of("待处理", "维修中", "待确认", "已完成", "已取消");

    /** 每个订单最多上传的图片数量 */
    private static final int MAX_IMAGES_PER_ORDER = 3;

    @Autowired
    private RepairOrderService repairOrderService;

    @Autowired
    private DormitoryService dormitoryService;

    @Autowired
    private OrderImageService orderImageService;

    @Autowired
    private FileStorage fileStorage;

    /**
     * 提交报修
     *
     * 报修人一律取令牌中的用户ID，不信任请求体里的 userId，避免替他人提交报修。
     */
    @PostMapping
    @Operation(summary = "提交报修", description = "学生提交新的报修申请")
    public ResponseEntity<Map<String, Object>> createOrder(
            @Parameter(description = "报修订单信息", required = true) @RequestBody RepairOrder order,
            HttpServletRequest request) {
        Long userId = AuthUtil.currentUserId(request);
        Map<String, Object> result = new HashMap<>();

        if (userId == null) {
            return badRequest(result, "未授权，请先登录");
        }
        if (order.getProblemDesc() == null || order.getProblemDesc().isBlank()) {
            return badRequest(result, "问题描述不能为空");
        }
        log.info("提交报修：用户ID={}, 报修内容={}", userId, order.getProblemDesc());

        // 查询用户绑定的宿舍信息
        Dormitory dormitory = dormitoryService.getByUserId(userId);
        if (dormitory == null) {
            log.warn("提交报修失败：用户未绑定宿舍，用户ID={}", userId);
            return badRequest(result, "请先绑定宿舍");
        }

        // 身份、地址和初始状态一律由服务端确定，防止前端伪造
        order.setOrderId(null);
        order.setUserId(userId);
        order.setRepairmanId(null);
        order.setDormId(dormitory.getDormId());
        order.setBuilding(dormitory.getBuilding());
        order.setRoomNum(dormitory.getRoomNum());
        order.setOrderStatus("待处理");
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        boolean success = repairOrderService.save(order);
        if (success) {
            log.info("提交报修成功：订单ID={}", order.getOrderId());
            result.put("success", true);
            result.put("message", "报修成功");
            result.put("data", order);
            return new ResponseEntity<>(result, HttpStatus.CREATED);
        } else {
            log.warn("提交报修失败：用户ID={}", userId);
            result.put("success", false);
            result.put("message", "报修失败");
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 查询报修单列表
     *
     * 学生只能查自己的报修单，维修人员只能查待处理订单或自己接取的订单，管理员不受限制。
     */
    @GetMapping
    @Operation(summary = "查询报修单列表", description = "根据条件查询报修单列表，支持分页")
    public ResponseEntity<Map<String, Object>> listOrders(
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "订单状态，多个状态用英文逗号分隔") @RequestParam(required = false) String status,
            @Parameter(description = "维修人员ID") @RequestParam(required = false) Long repairmanId,
            @Parameter(description = "是否包含用户信息") @RequestParam(required = false) Boolean includeUserInfo,
            @Parameter(description = "页码，默认1") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页大小，默认5") @RequestParam(defaultValue = "5") Integer size,
            HttpServletRequest request
    ) {
        Map<String, Object> result = new HashMap<>();
        Long currentUserId = AuthUtil.currentUserId(request);

        if (AuthUtil.isStudent(request)) {
            // 学生只能查看自己的报修单
            userId = currentUserId;
            repairmanId = null;
            includeUserInfo = false;
        } else if (AuthUtil.isRepairman(request)) {
            if (userId != null) {
                return forbidden(result, "无权查看其他学生的报修单");
            }
            if (repairmanId != null && !Objects.equals(repairmanId, currentUserId)) {
                return forbidden(result, "只能查看自己接取的报修单");
            }
            includeUserInfo = false;
        }

        log.info("查询报修单列表：用户ID={}, 状态={}, 维修人员ID={}, 包含用户信息={}, 页码={}, 每页大小={}",
                userId, status, repairmanId, includeUserInfo, page, size);

        // 如果指定了includeUserInfo且为true，返回包含用户信息的分页订单
        if (Boolean.TRUE.equals(includeUserInfo)) {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> pageObj = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
            com.baomidou.mybatisplus.core.metadata.IPage<OrderWithUserDTO> pageResult = repairOrderService.getPageWithUserInfo(pageObj, status);
            log.info("查询成功：总记录数={}, 总页数={}", pageResult.getTotal(), pageResult.getPages());
            result.put("success", true);
            result.put("data", pageResult);
        } else {
            // 普通查询
            if (userId != null) {
                // 根据用户ID查询
                List<RepairOrder> orders = repairOrderService.getByUserId(userId, status);
                log.info("查询成功：用户ID={}, 订单数量={}", userId, orders.size());
                result.put("success", true);
                result.put("data", orders);
            } else if (repairmanId != null) {
                // 根据维修人员ID查询
                List<RepairOrder> orders = repairOrderService.getByRepairmanId(repairmanId, status);
                log.info("查询成功：维修人员ID={}, 订单数量={}", repairmanId, orders.size());
                result.put("success", true);
                result.put("data", orders);
            } else if (status != null) {
                // 根据状态查询
                List<RepairOrder> orders = repairOrderService.getByOrderStatus(status);
                log.info("查询成功：状态={}, 订单数量={}", status, orders.size());
                result.put("success", true);
                result.put("data", orders);
            } else {
                // 分页查询所有
                com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairOrder> pageObj = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
                com.baomidou.mybatisplus.core.metadata.IPage<RepairOrder> pageResult = repairOrderService.getPage(pageObj, status);
                log.info("查询成功：总记录数={}, 总页数={}", pageResult.getTotal(), pageResult.getPages());
                result.put("success", true);
                result.put("data", pageResult);
            }
        }
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 修改报修状态
     *
     * 学生只能取消自己的订单或在自己订单维修完成后确认完成；维修人员只能推进自己接取的订单；管理员不受限制。
     */
    @PutMapping("/{orderId}/status")
    @Operation(summary = "修改报修状态", description = "更新报修订单的状态")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @Parameter(description = "订单ID", required = true) @PathVariable Long orderId,
            @Parameter(description = "状态更新信息，包含status字段", required = true) @RequestBody Map<String, String> statusUpdate,
            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        String newStatus = statusUpdate.get("status");
        if (newStatus == null || newStatus.isBlank()) {
            return badRequest(result, "状态不能为空");
        }
        newStatus = newStatus.trim();
        if (!VALID_STATUSES.contains(newStatus)) {
            return badRequest(result, "非法的订单状态：" + newStatus);
        }

        RepairOrder order = repairOrderService.getById(orderId);
        if (order == null) {
            return notFound(result, "订单不存在");
        }

        Long currentUserId = AuthUtil.currentUserId(request);
        log.info("修改报修状态：订单ID={}, 新状态={}, 操作人={}", orderId, newStatus, currentUserId);

        if (AuthUtil.isStudent(request)) {
            if (!Objects.equals(currentUserId, order.getUserId())) {
                return forbidden(result, "只能操作自己的报修单");
            }
            if ("已取消".equals(newStatus)) {
                if (!"待处理".equals(order.getOrderStatus()) && !"维修中".equals(order.getOrderStatus())) {
                    return badRequest(result, "当前状态不允许取消");
                }
            } else if ("已完成".equals(newStatus)) {
                if (!"待确认".equals(order.getOrderStatus())) {
                    return badRequest(result, "只有维修中的订单可以确认完成");
                }
            } else {
                return forbidden(result, "学生只能取消订单或确认完成");
            }
        } else if (AuthUtil.isRepairman(request)) {
            if (!Objects.equals(currentUserId, order.getRepairmanId())) {
                return forbidden(result, "只能操作自己接取的报修单");
            }
            if (!"维修中".equals(newStatus) && !"待确认".equals(newStatus)) {
                return forbidden(result, "维修人员只能把订单置为维修中或待确认");
            }
        }

        RepairOrder update = new RepairOrder();
        update.setOrderId(orderId);
        update.setOrderStatus(newStatus);
        update.setUpdateTime(LocalDateTime.now());

        boolean success = repairOrderService.updateById(update);
        if (success) {
            log.info("修改成功：订单ID={}", orderId);
            result.put("success", true);
            result.put("message", "修改成功");
            return new ResponseEntity<>(result, HttpStatus.OK);
        } else {
            log.warn("修改失败：订单ID={}", orderId);
            result.put("success", false);
            result.put("message", "修改失败");
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 删除报修单（同时清理订单图片和本地文件）
     */
    @DeleteMapping("/{orderId}")
    @Operation(summary = "删除报修单", description = "根据订单ID删除报修单")
    public ResponseEntity<Map<String, Object>> deleteOrder(
            @Parameter(description = "订单ID", required = true) @PathVariable Long orderId,
            HttpServletRequest request) {
        log.info("删除报修单：订单ID={}", orderId);
        Map<String, Object> result = new HashMap<>();

        RepairOrder order = repairOrderService.getById(orderId);
        if (order == null) {
            return notFound(result, "订单不存在");
        }

        if (!AuthUtil.isAdmin(request)) {
            // 学生只能删除自己还没被接单的报修单
            if (!AuthUtil.isStudent(request) || !Objects.equals(AuthUtil.currentUserId(request), order.getUserId())) {
                return forbidden(result, "只能删除自己的报修单");
            }
            if (!"待处理".equals(order.getOrderStatus())) {
                return badRequest(result, "只能删除待处理的报修单");
            }
        }

        boolean success = repairOrderService.deleteOrderWithImages(orderId);
        if (success) {
            log.info("删除成功：订单ID={}", orderId);
            result.put("success", true);
            result.put("message", "删除成功");
            return new ResponseEntity<>(result, HttpStatus.OK);
        } else {
            log.warn("删除失败：订单ID={}", orderId);
            result.put("success", false);
            result.put("message", "删除失败");
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 接取订单
     *
     * 维修人员ID取令牌中的用户ID，不信任请求体传值。
     */
    @PostMapping("/{orderId}/accept")
    @Operation(summary = "接取订单", description = "维修人员接取报修订单")
    public ResponseEntity<Map<String, Object>> acceptOrder(
            @Parameter(description = "订单ID", required = true) @PathVariable Long orderId,
            HttpServletRequest request) {
        Long repairmanId = AuthUtil.currentUserId(request);
        log.info("接取订单：订单ID={}, 维修人员ID={}", orderId, repairmanId);
        Map<String, Object> result = new HashMap<>();

        if (repairmanId == null) {
            return badRequest(result, "未授权，请先登录");
        }

        RepairOrder order = repairOrderService.getById(orderId);
        if (order == null) {
            return notFound(result, "订单不存在");
        }
        if (!"待处理".equals(order.getOrderStatus())) {
            return badRequest(result, "该订单已被接取或已结束");
        }

        boolean success = repairOrderService.acceptOrder(orderId, repairmanId);
        if (success) {
            log.info("接取订单成功：订单ID={}", orderId);
            result.put("success", true);
            result.put("message", "接取订单成功");
            return new ResponseEntity<>(result, HttpStatus.OK);
        } else {
            log.warn("接取订单失败：订单ID={}", orderId);
            result.put("success", false);
            result.put("message", "接取订单失败，订单可能已被其他维修人员接取");
            return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 上传报修图片（每个订单最多3张，仅图片类型）
     */
    @PostMapping("/{orderId}/images")
    @Operation(summary = "上传报修图片", description = "为报修订单上传图片")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @Parameter(description = "订单ID", required = true) @PathVariable Long orderId,
            @Parameter(description = "图片文件", required = true) @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        log.info("上传报修图片：订单ID={}, 文件名={}", orderId, file != null ? file.getOriginalFilename() : null);
        Map<String, Object> result = new HashMap<>();

        if (file == null || file.isEmpty()) {
            log.warn("上传失败：文件为空");
            return badRequest(result, "文件不能为空");
        }

        // 检查订单是否存在
        RepairOrder order = repairOrderService.getById(orderId);
        if (order == null) {
            log.warn("上传失败：订单不存在，订单ID={}", orderId);
            return notFound(result, "订单不存在");
        }
        if (!canAccessOrder(order, request)) {
            return forbidden(result, "无权为该报修单上传图片");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return badRequest(result, "只能上传图片文件");
        }

        // 服务端兜底限制图片数量，不能只靠前端限制
        int imageCount = orderImageService.getByOrderId(orderId).size();
        if (imageCount >= MAX_IMAGES_PER_ORDER) {
            return badRequest(result, "每个报修单最多上传" + MAX_IMAGES_PER_ORDER + "张图片");
        }

        try {
            // 把图片保存到本地存储
            String imageUrl = fileStorage.uploadImage(file, "repair-orders");

            // 保存图片信息到数据库
            OrderImage orderImage = new OrderImage();
            orderImage.setOrderId(orderId);
            orderImage.setImageUrl(imageUrl);
            orderImage.setCreateTime(LocalDateTime.now());

            boolean saveSuccess = orderImageService.saveOrderImage(orderImage);
            if (saveSuccess) {
                log.info("上传成功：订单ID={}, 图片URL={}", orderId, orderImage.getImageUrl());
                result.put("success", true);
                result.put("message", "上传成功");
                result.put("data", orderImage);
                return new ResponseEntity<>(result, HttpStatus.CREATED);
            } else {
                log.warn("上传失败：保存图片信息失败，订单ID={}", orderId);
                result.put("success", false);
                result.put("message", "上传失败，请稍后重试");
                return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } catch (IOException e) {
            log.error("上传失败：文件保存失败", e);
            result.put("success", false);
            result.put("message", "上传失败：" + e.getMessage());
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 获取订单图片列表
     */
    @GetMapping("/{orderId}/images")
    @Operation(summary = "获取订单图片列表", description = "获取指定订单的图片列表")
    public ResponseEntity<Map<String, Object>> getOrderImages(
            @Parameter(description = "订单ID", required = true) @PathVariable Long orderId,
            HttpServletRequest request) {
        log.info("获取订单图片列表：订单ID={}", orderId);
        Map<String, Object> result = new HashMap<>();

        RepairOrder order = repairOrderService.getById(orderId);
        if (order == null) {
            return notFound(result, "订单不存在");
        }
        if (!canAccessOrder(order, request)) {
            return forbidden(result, "无权查看该报修单的图片");
        }

        List<OrderImage> images = orderImageService.getByOrderId(orderId);
        log.info("查询成功：订单ID={}, 图片数量={}", orderId, images.size());
        result.put("success", true);
        result.put("data", images);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 删除订单图片
     */
    @DeleteMapping("/images/{imageId}")
    @Operation(summary = "删除订单图片", description = "删除指定的订单图片")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @Parameter(description = "图片ID", required = true) @PathVariable Long imageId,
            HttpServletRequest request) {
        log.info("删除订单图片：图片ID={}", imageId);
        Map<String, Object> result = new HashMap<>();

        // 获取图片信息
        OrderImage orderImage = orderImageService.getById(imageId);
        if (orderImage == null) {
            log.warn("删除失败：图片不存在，图片ID={}", imageId);
            return badRequest(result, "图片不存在");
        }

        RepairOrder order = repairOrderService.getById(orderImage.getOrderId());
        if (order != null && !canAccessOrder(order, request)) {
            return forbidden(result, "无权删除该报修单的图片");
        }

        try {
            // 从数据库删除记录
            fileStorage.deleteImage(orderImage.getImageUrl());
            boolean success = orderImageService.removeById(imageId);
            if (success) {
                log.info("删除成功：图片ID={}, 图片URL={}", imageId, orderImage.getImageUrl());
                result.put("success", true);
                result.put("message", "删除成功");
                return new ResponseEntity<>(result, HttpStatus.OK);
            } else {
                log.warn("删除失败：图片ID={}", imageId);
                result.put("success", false);
                result.put("message", "删除失败");
                return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } catch (Exception e) {
            log.error("删除失败：", e);
            result.put("success", false);
            result.put("message", "删除失败");
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 当前登录用户是否有权访问该订单：管理员不限，学生限本人报修单，维修人员限本人接取的报修单
     */
    /**
     * 按设备类型统计工单数量（仅管理员，数量倒序）
     */
    @GetMapping("/stats/device-type")
    public ResponseEntity<Map<String, Object>> statsByDeviceType(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (!AuthUtil.isAdmin(request)) {
            return forbidden(result, "无权访问");
        }

        Map<String, Integer> counter = new HashMap<>();
        for (RepairOrder order : repairOrderService.list()) {
            String type = order.getDeviceType();
            if (type == null || type.isBlank()) {
                continue;
            }
            counter.merge(type, 1, Integer::sum);
        }

        List<Map<String, Object>> data = counter.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("deviceType", e.getKey());
                    item.put("count", e.getValue());
                    return item;
                })
                .toList();

        log.info("按设备类型统计：{}", data);
        result.put("success", true);
        result.put("data", data);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    private boolean canAccessOrder(RepairOrder order, HttpServletRequest request) {
        if (AuthUtil.isAdmin(request)) {
            return true;
        }
        Long currentUserId = AuthUtil.currentUserId(request);
        if (AuthUtil.isStudent(request)) {
            return Objects.equals(currentUserId, order.getUserId());
        }
        if (AuthUtil.isRepairman(request)) {
            return Objects.equals(currentUserId, order.getRepairmanId());
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> badRequest(Map<String, Object> result, String message) {
        result.put("success", false);
        result.put("message", message);
        return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> forbidden(Map<String, Object> result, String message) {
        result.put("success", false);
        result.put("message", message);
        return new ResponseEntity<>(result, HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<Map<String, Object>> notFound(Map<String, Object> result, String message) {
        result.put("success", false);
        result.put("message", message);
        return new ResponseEntity<>(result, HttpStatus.NOT_FOUND);
    }
}
