package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaBulkFileAdapterTest {

    private final SpringDataBulkFileRepository repository = mock(SpringDataBulkFileRepository.class);
    private final JpaBulkFileAdapter adapter = new JpaBulkFileAdapter(repository);

    @Test
    void insertsANewFile() {
        BulkFile file = BulkFilePersistenceMapperTest.newFile();
        when(repository.findById("FILE-1")).thenReturn(Optional.empty());

        assertThat(adapter.save(file)).isSameAs(file);

        verify(repository).saveAndFlush(any(BulkFileJpaEntity.class));
    }

    @Test
    void updatesProgressWhenTheVersionMatches() {
        BulkFile file = BulkFilePersistenceMapperTest.newFile();
        BulkFileJpaEntity stored = BulkFilePersistenceMapper.toNewEntity(file);
        when(repository.findById("FILE-1")).thenReturn(Optional.of(stored));
        file.recordProcessedBatch(1, BulkFilePersistenceMapperTest.AT);

        adapter.save(file);

        assertThat(stored.getProcessedCount()).isEqualTo(1);
        verify(repository).saveAndFlush(stored);
    }

    @Test
    void refusesAStaleVersion() {
        BulkFile file = BulkFilePersistenceMapperTest.newFile();
        BulkFileJpaEntity stored = BulkFilePersistenceMapper.toNewEntity(file);
        ReflectionTestUtils.setField(stored, "version", 3L);
        when(repository.findById("FILE-1")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> adapter.save(file)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void readsAndClaimsThroughTheMapper() {
        BulkFileJpaEntity stored = BulkFilePersistenceMapper.toNewEntity(BulkFilePersistenceMapperTest.newFile());
        when(repository.findById("FILE-1")).thenReturn(Optional.of(stored));
        when(repository.lockProcessing("FILE-1")).thenReturn(Optional.of(stored));
        when(repository.findProcessing(5)).thenReturn(java.util.List.of(stored));

        assertThat(adapter.findById("FILE-1")).map(BulkFile::fileId).contains("FILE-1");
        assertThat(adapter.claimProcessing("FILE-1")).map(BulkFile::tppId).contains("TPP-001");
        assertThat(adapter.findProcessing(5)).extracting(BulkFile::fileId).containsExactly("FILE-1");
    }
}
