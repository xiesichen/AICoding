package org.example.dormrepairsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.dormrepairsystem.dto.OrderWithUserDTO;
import org.example.dormrepairsystem.entity.OrderImage;
import org.example.dormrepairsystem.entity.RepairOrder;
import org.example.dormrepairsystem.entity.User;
import org.example.dormrepairsystem.mapper.RepairOrderMapper;
import org.example.dormrepairsystem.service.OrderImageService;
import org.example.dormrepairsystem.service.RepairOrderService;
import org.example.dormrepairsystem.service.UserService;
import org.example.dormrepairsystem.util.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 报修单核心服务实现类
 */
@Service
@Slf4j
public class RepairOrderServiceImpl extends ServiceImpl<RepairOrderMapper, RepairOrder> implements RepairOrderService {

    @Autowired
    private UserService userService;

    @Autowired
    private OrderImageService orderImageService;

    @Autowired
    private FileStorage fileStorage;

    // 根据用户ID查询个人报修记录
    @Override
    public List<RepairOrder> getByUserId(Long userId, String status) {
        LambdaQueryWrapper<RepairOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RepairOrder::getUserId, userId);
        applyStatusFilter(wrapper, status);
        wrapper.orderByDesc(RepairOrder::getCreateTime); // 按创建时间倒序
        return this.list(wrapper);
    }

    // 根据状态查询报修单列表
    @Override
    public List<RepairOrder> getByOrderStatus(String status) {
        LambdaQueryWrapper<RepairOrder> wrapper = new LambdaQueryWrapper<>();
        applyStatusFilter(wrapper, status);
        wrapper.orderByDesc(RepairOrder::getCreateTime);
        return this.list(wrapper);
    }

    // 分页查询（支持状态筛选，status为null则查全部）
    @Override
    public IPage<RepairOrder> getPage(Page<RepairOrder> page, String status) {
        LambdaQueryWrapper<RepairOrder> wrapper = new LambdaQueryWrapper<>();
        applyStatusFilter(wrapper, status);
        wrapper.orderByDesc(RepairOrder::getUpdateTime); // 按最后修改时间倒序
        return this.page(page, wrapper);
    }

    // 接取订单
    @Override
    public boolean acceptOrder(Long orderId, Long repairmanId) {
        RepairOrder order = this.getById(orderId);
        if (order != null && "待处理".equals(order.getOrderStatus())) {
            order.setRepairmanId(repairmanId);
            order.setOrderStatus("维修中");
            order.setUpdateTime(java.time.LocalDateTime.now());
            return this.updateById(order);
        }
        return false;
    }

    // 根据维修人员ID查询订单
    @Override
    public List<RepairOrder> getByRepairmanId(Long repairmanId, String status) {
        LambdaQueryWrapper<RepairOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RepairOrder::getRepairmanId, repairmanId);
        applyStatusFilter(wrapper, status);
        wrapper.orderByDesc(RepairOrder::getUpdateTime); // 按最后修改时间倒序
        return this.list(wrapper);
    }

    // 分页查询订单（包含用户信息，管理员专属）
    @Override
    public IPage<OrderWithUserDTO> getPageWithUserInfo(Page<RepairOrder> page, String status) {
        LambdaQueryWrapper<RepairOrder> wrapper = new LambdaQueryWrapper<>();
        applyStatusFilter(wrapper, status);
        wrapper.orderByDesc(RepairOrder::getCreateTime);

        // 先分页查出订单，再把当页记录转换成带用户信息的DTO
        IPage<RepairOrder> orderPage = this.page(page, wrapper);

        Page<OrderWithUserDTO> dtoPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        List<OrderWithUserDTO> dtos = new ArrayList<>();
        for (RepairOrder order : orderPage.getRecords()) {
            dtos.add(toOrderWithUserDTO(order));
        }
        dtoPage.setRecords(dtos);

        return dtoPage;
    }

    // 删除订单并清理其图片（本地文件 + 数据库记录），避免留下孤儿数据
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteOrderWithImages(Long orderId) {
        List<OrderImage> images = orderImageService.getByOrderId(orderId);
        for (OrderImage image : images) {
            try {
                fileStorage.deleteImage(image.getImageUrl());
            } catch (Exception e) {
                // 文件删除失败不影响数据库清理，避免图片删不掉导致订单也删不掉
                log.warn("删除订单 {} 的本地图片失败：{}", orderId, e.getMessage());
            }
        }
        orderImageService.deleteByOrderId(orderId);
        return this.removeById(orderId);
    }

    /**
     * 状态筛选：支持逗号分隔的多个状态，例如 "已完成,已取消"
     */
    private void applyStatusFilter(LambdaQueryWrapper<RepairOrder> wrapper, String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        List<String> statuses = Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (statuses.size() == 1) {
            wrapper.eq(RepairOrder::getOrderStatus, statuses.get(0));
        } else if (statuses.size() > 1) {
            wrapper.in(RepairOrder::getOrderStatus, statuses);
        }
    }

    // 将订单实体转换为包含用户信息的DTO
    private OrderWithUserDTO toOrderWithUserDTO(RepairOrder order) {
        OrderWithUserDTO dto = new OrderWithUserDTO();
        // 复制订单基本信息
        dto.setOrderId(order.getOrderId());
        dto.setUserId(order.getUserId());
        dto.setDormId(order.getDormId());
        dto.setBuilding(order.getBuilding());
        dto.setRoomNum(order.getRoomNum());
        dto.setRepairmanId(order.getRepairmanId());
        dto.setDeviceType(order.getDeviceType());
        dto.setProblemDesc(order.getProblemDesc());
        dto.setOrderStatus(order.getOrderStatus());
        dto.setCreateTime(order.getCreateTime());
        dto.setUpdateTime(order.getUpdateTime());

        // 查询学生信息
        if (order.getUserId() != null) {
            User student = userService.getById(order.getUserId());
            if (student != null) {
                dto.setUserName(student.getUserName());
            }
        }

        // 查询维修人员信息
        if (order.getRepairmanId() != null) {
            User repairman = userService.getById(order.getRepairmanId());
            if (repairman != null) {
                dto.setRepairmanName(repairman.getUserName());
            }
        }

        return dto;
    }
}
