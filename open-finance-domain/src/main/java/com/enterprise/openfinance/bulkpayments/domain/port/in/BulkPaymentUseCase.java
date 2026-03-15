package com.enterprise.openfinance.bulkpayments.domain.port.in;

import com.enterprise.openfinance.bulkpayments.domain.command.SubmitBulkFileCommand;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFile;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkFileReport;
import com.enterprise.openfinance.bulkpayments.domain.model.BulkUploadResult;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileReportQuery;
import com.enterprise.openfinance.bulkpayments.domain.query.GetBulkFileStatusQuery;

import java.util.Optional;

public interface BulkPaymentUseCase {

    BulkUploadResult submitFile(SubmitBulkFileCommand command);

    Optional<BulkFile> getFileStatus(GetBulkFileStatusQuery query);

    Optional<BulkFileReport> getFileReport(GetBulkFileReportQuery query);
}
