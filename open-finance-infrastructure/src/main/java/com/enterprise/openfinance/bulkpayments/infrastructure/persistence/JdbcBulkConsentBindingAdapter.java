package com.enterprise.openfinance.bulkpayments.infrastructure.persistence;

import com.enterprise.openfinance.bulkpayments.domain.model.BulkConsentBinding;
import com.enterprise.openfinance.bulkpayments.domain.model.Money;
import com.enterprise.openfinance.bulkpayments.domain.port.out.BulkConsentBindingPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Currency;
import java.util.Optional;

/**
 * Single-use consent bindings in sc_pay_bulk_orchestration.bulk_consent_binding.
 * consent_id is the primary key and the insert is ON CONFLICT DO NOTHING: a
 * concurrent upload on the same consent waits for the first transaction and then
 * inserts nothing, so only one file per consent is ever committed.
 */
@Repository
public class JdbcBulkConsentBindingAdapter implements BulkConsentBindingPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBulkConsentBindingAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean bind(BulkConsentBinding binding) {
        int rows = jdbc.update("""
                insert into bulk_consent_binding
                    (consent_id, tpp_id, file_id, file_hash, item_count, control_sum, currency, bound_at)
                values (:consentId, :tppId, :fileId, :fileHash, :itemCount, :controlSum, :currency, :boundAt)
                on conflict (consent_id) do nothing
                """, new MapSqlParameterSource("consentId", binding.consentId())
                .addValue("tppId", binding.tppId())
                .addValue("fileId", binding.fileId())
                .addValue("fileHash", binding.fileHash())
                .addValue("itemCount", binding.itemCount())
                .addValue("controlSum", binding.controlSum().amount())
                .addValue("currency", binding.controlSum().currency().getCurrencyCode())
                .addValue("boundAt", Timestamp.from(binding.boundAt())));
        return rows == 1;
    }

    @Override
    public Optional<BulkConsentBinding> findByConsentId(String consentId) {
        return jdbc.query("""
                        select consent_id, tpp_id, file_id, file_hash, item_count, control_sum, currency, bound_at
                        from bulk_consent_binding where consent_id = :consentId
                        """, new MapSqlParameterSource("consentId", consentId),
                (rs, rowNum) -> new BulkConsentBinding(
                        rs.getString("consent_id"),
                        rs.getString("tpp_id"),
                        rs.getString("file_id"),
                        rs.getString("file_hash"),
                        rs.getInt("item_count"),
                        new Money(rs.getBigDecimal("control_sum"), Currency.getInstance(rs.getString("currency"))),
                        rs.getTimestamp("bound_at").toInstant()))
                .stream().findFirst();
    }
}
