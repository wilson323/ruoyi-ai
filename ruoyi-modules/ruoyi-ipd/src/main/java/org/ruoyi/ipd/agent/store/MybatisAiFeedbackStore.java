package org.ruoyi.ipd.agent.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.agent.domain.IpdAiFeedback;
import org.ruoyi.ipd.agent.mapper.IpdAiFeedbackMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link AiFeedbackStore} 的 MyBatis-Plus 实现：更新按唯一键三元组条件定位，
 * 保证只能改本人的那一行。
 */
@Repository
@RequiredArgsConstructor
public class MybatisAiFeedbackStore implements AiFeedbackStore {

    private final IpdAiFeedbackMapper mapper;

    /** {@inheritDoc} */
    @Override
    public Optional<IpdAiFeedback> find(String targetType, Long targetId, Long personId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<IpdAiFeedback>()
            .eq(IpdAiFeedback::getTargetType, targetType)
            .eq(IpdAiFeedback::getTargetId, targetId)
            .eq(IpdAiFeedback::getPersonId, personId)));
    }

    /** {@inheritDoc} */
    @Override
    public boolean insert(IpdAiFeedback feedback) {
        try {
            return mapper.insert(feedback) == 1;
        } catch (DuplicateKeyException duplicated) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean updateRating(String targetType, Long targetId, Long personId, String rating, String reason) {
        return mapper.update(null, new LambdaUpdateWrapper<IpdAiFeedback>()
            .eq(IpdAiFeedback::getTargetType, targetType)
            .eq(IpdAiFeedback::getTargetId, targetId)
            .eq(IpdAiFeedback::getPersonId, personId)
            .set(IpdAiFeedback::getRating, rating)
            .set(IpdAiFeedback::getReason, reason)) == 1;
    }
}
