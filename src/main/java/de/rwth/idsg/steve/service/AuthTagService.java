package de.rwth.idsg.steve.service;

import static de.rwth.idsg.steve.utils.OcppTagActivityRecordUtils.isBlocked;
import static de.rwth.idsg.steve.utils.OcppTagActivityRecordUtils.isExpired;
import static de.rwth.idsg.steve.utils.OcppTagActivityRecordUtils.reachedLimitOfActiveTransactions;

import de.rwth.idsg.steve.repository.OcppTagRepository;
import de.rwth.idsg.steve.repository.SettingsRepository;
import jooq.steve.db.tables.records.OcppTagActivityRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ocpp.cs._2015._10.AuthorizationStatus;
import ocpp.cs._2015._10.IdTagInfo;
import org.jetbrains.annotations.Nullable;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthTagService {
  private final OcppTagRepository ocppTagRepository;
  private final SettingsRepository settingsRepository;

  public IdTagInfo decideStatus(String idTag,
                                boolean isStartTransactionReqContext,
                                @Nullable String chargeBoxId,
                                @Nullable Integer connectorId) {
    OcppTagActivityRecord record = ocppTagRepository.getRecord(idTag);
    if (record == null) {
      log.error("The user with idTag '{}' is INVALID (not present in DB).", idTag);
      return new IdTagInfo().withStatus(AuthorizationStatus.INVALID);
    }

    if (isBlocked(record)) {
      log.error("The user with idTag '{}' is BLOCKED.", idTag);
      return new IdTagInfo()
              .withStatus(AuthorizationStatus.BLOCKED)
              .withParentIdTag(record.getParentIdTag())
              .withExpiryDate(getExpiryDateOrDefault(record));
    }

    if (isExpired(record, DateTime.now())) {
      log.error("The user with idTag '{}' is EXPIRED.", idTag);
      return new IdTagInfo()
              .withStatus(AuthorizationStatus.EXPIRED)
              .withParentIdTag(record.getParentIdTag())
              .withExpiryDate(getExpiryDateOrDefault(record));
    }

    // https://github.com/steve-community/steve/issues/219
    if (isStartTransactionReqContext && reachedLimitOfActiveTransactions(record)) {
      log.warn("The user with idTag '{}' is ALREADY in another transaction(s).", idTag);
      return new IdTagInfo()
              .withStatus(AuthorizationStatus.CONCURRENT_TX)
              .withParentIdTag(record.getParentIdTag())
              .withExpiryDate(getExpiryDateOrDefault(record));
    }

    log.debug("The user with idTag '{}' is ACCEPTED.", record.getIdTag());
    return new IdTagInfo()
            .withStatus(AuthorizationStatus.ACCEPTED)
            .withParentIdTag(record.getParentIdTag())
            .withExpiryDate(getExpiryDateOrDefault(record));
  }

  /**
   * If the database contains an actual expiry, use it. Otherwise, calculate an expiry for cached info
   */
  @Nullable
  private DateTime getExpiryDateOrDefault(OcppTagActivityRecord record) {
    if (record.getExpiryDate() != null) {
      return record.getExpiryDate();
    }

    int hoursToExpire = settingsRepository.getHoursToExpire();

    // From web page: The value 0 disables this functionality (i.e. no expiry date will be set).
    if (hoursToExpire == 0) {
      return null;
    } else {
      return DateTime.now().plusHours(hoursToExpire);
    }
  }
}
