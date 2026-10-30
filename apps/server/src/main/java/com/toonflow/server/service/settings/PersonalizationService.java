package com.toonflow.server.service.settings;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.RevisionUtils;
import com.toonflow.server.entity.SysPersonalization;
import com.toonflow.server.mapper.SysPersonalizationMapper;
import com.toonflow.server.service.DataSnapshotSyncService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 正文仅写 sys_personalization，磁盘文件由提交后的快照同步器派生。 */
@Service
public class PersonalizationService {
    private static final int MAX_LENGTH = 20000;
    private final SysPersonalizationMapper mapper;
    private final DataSnapshotSyncService snapshotSync;
    private final TransactionTemplate transaction;
    private final Map<String, ReentrantLock> locks = Map.of(
            "memory", new ReentrantLock(true), "agents", new ReentrantLock(true));

    public PersonalizationService(SysPersonalizationMapper mapper, DataSnapshotSyncService snapshotSync,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.snapshotSync = snapshotSync;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public DocumentSnapshot get(String document) {
        requireDocument(document);
        SysPersonalization current = mapper.selectById(document);
        String content = current == null ? "" : current.getContent();
        requireLength(content);
        return new DocumentSnapshot(content, RevisionUtils.sha256(content));
    }

    public DocumentSnapshot save(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("document").isTextual()
                || !body.path("content").isTextual() || !body.path("revision").isTextual()) {
            throw new BusinessException(400, "参数错误", null);
        }
        String document = body.get("document").textValue();
        requireDocument(document);
        String revision = body.get("revision").textValue();
        if (!revision.matches("[a-f0-9]{64}")) {
            throw new BusinessException(400, "revision 必须为 SHA-256 小写摘要", null);
        }
        String content = body.get("content").textValue();
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        content = content.replace("\r\n", "\n").replace('\r', '\n');
        requireLength(content);
        DocumentSnapshot next = new DocumentSnapshot(content, RevisionUtils.sha256(content));
        ReentrantLock lock = locks.get(document);
        lock.lock();
        try {
            // 锁包住事务提交与同步回调，避免同文档下一次写入先于本次提交。
            return Objects.requireNonNull(transaction.execute(status -> saveDocument(document, revision, next)));
        } catch (DuplicateKeyException exception) {
            // 首次保存若被其他进程抢先插入，同样属于 CAS 冲突；事务已回滚。
            throw stale();
        } finally {
            lock.unlock();
        }
    }

    private DocumentSnapshot saveDocument(String document, String revision, DocumentSnapshot next) {
        SysPersonalization current = mapper.selectById(document);
        String currentContent = current == null ? "" : current.getContent();
        if (!revision.equals(RevisionUtils.sha256(currentContent))) {
            throw stale();
        }
        if (current == null) {
            SysPersonalization created = new SysPersonalization();
            created.setDocType(document);
            created.setContent(next.content());
            created.setRevision(next.revision());
            created.setUpdatedAt(OffsetDateTime.now());
            mapper.insert(created);
        } else {
            int updated = mapper.update(new LambdaUpdateWrapper<SysPersonalization>()
                    .eq(SysPersonalization::getDocType, document)
                    .eq(SysPersonalization::getRevision, revision)
                    .eq(SysPersonalization::getContent, currentContent)
                    .set(SysPersonalization::getContent, next.content())
                    .set(SysPersonalization::getRevision, next.revision())
                    .set(SysPersonalization::getUpdatedAt, OffsetDateTime.now()));
            if (updated != 1) {
                throw stale();
            }
        }
        snapshotSync.syncAfterCommit();
        return next;
    }

    private void requireDocument(String document) {
        if (document == null || !locks.containsKey(document)) {
            throw new BusinessException(400, "document 必须为 memory 或 agents", null);
        }
    }

    private void requireLength(String content) {
        if (content.length() > MAX_LENGTH) {
            throw new BusinessException(400, "内容不能超过 20000 字", null);
        }
    }

    private BusinessException stale() {
        return new BusinessException(409, "内容已被其他位置修改，请刷新后再试", Map.of("code", "ESTALE"));
    }

    public record DocumentSnapshot(String content, String revision) {
    }
}
