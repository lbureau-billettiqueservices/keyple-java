/* **************************************************************************************
 * Copyright (c) 2018 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information
 * regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ************************************************************************************** */
package org.eclipse.keyple.calypso.transaction;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.keyple.calypso.command.PoClass;
import org.eclipse.keyple.calypso.command.po.PoRevision;
import org.eclipse.keyple.calypso.command.po.parser.GetDataFciRespPars;
import org.eclipse.keyple.core.card.message.CardSelectionResponse;
import org.eclipse.keyple.core.card.selection.AbstractSmartCard;
import org.eclipse.keyple.core.util.ByteArrayUtil;

/**
 * This POJO concentrates all the information we know about the PO being processed: from the
 * selection stage to the end of the transaction.
 *
 * <p>An instance of CalypsoPo is obtained by casting the AbstractSmartCard object from the
 * selection process (e.g. (CalypsoPo)(cardSelectionsResult.getActiveSmartCard()))
 *
 * <p>The various information contained in CalypsoPo is accessible by getters and includes:
 *
 * <ul>
 *   <li>The application identification fields (revision/version, class, DF name, serial number,
 *       ATR, issuer)
 *   <li>The indication of the presence of optional features (Stored Value, PIN, Rev3.2 mode,
 *       ratification management)
 *   <li>The management information of the modification buffer
 *   <li>The invalidation status
 *   <li>The files, counters, SV data read or modified during the execution of the processes defined
 *       by {@link PoTransaction}
 * </ul>
 *
 * @since 0.9
 */
public class CalypsoPo extends AbstractSmartCard {
  private final boolean isConfidentialSessionModeSupported;
  private final boolean isDeselectRatificationSupported;
  private final boolean isSvFeatureAvailable;
  private final boolean isPinFeatureAvailable;
  private final boolean isPublicAuthenticationSupported;
  private final boolean isDfInvalidated;
  private final PoClass poClass;
  private final byte[] calypsoSerialNumber;
  private final byte[] startupInfo;
  private final PoRevision revision;
  private final byte[] dfName;
  private static final int PO_REV1_ATR_LENGTH = 20;
  private static final int REV1_PO_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION = 3;
  private static final int REV2_PO_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION = 6;
  private static final int SI_BUFFER_SIZE_INDICATOR = 0;
  private static final int SI_PLATFORM = 1;
  private static final int SI_APPLICATION_TYPE = 2;
  private static final int SI_APPLICATION_SUBTYPE = 3;
  private static final int SI_SOFTWARE_ISSUER = 4;
  private static final int SI_SOFTWARE_VERSION = 5;
  private static final int SI_SOFTWARE_REVISION = 6;

  // Application type bitmasks features
  private static final byte APP_TYPE_WITH_CALYPSO_PIN = 0x01;
  private static final byte APP_TYPE_WITH_CALYPSO_SV = 0x02;
  private static final byte APP_TYPE_RATIFICATION_COMMAND_REQUIRED = 0x04;
  private static final byte APP_TYPE_CALYPSO_REV_32_MODE = 0x08;
  private static final byte APP_TYPE_WITH_PUBLIC_AUTHENTICATION = 0x10;

  // buffer indicator to buffer size lookup table
  private static final int[] BUFFER_SIZE_INDICATOR_TO_BUFFER_SIZE =
      new int[] {
        0, 0, 0, 0, 0, 0, 215, 256, 304, 362, 430, 512, 608, 724, 861, 1024, 1217, 1448, 1722, 2048,
        2435, 2896, 3444, 4096, 4870, 5792, 6888, 8192, 9741, 11585, 13777, 16384, 19483, 23170,
        27554, 32768, 38967, 46340, 55108, 65536, 77935, 92681, 110217, 131072, 155871, 185363,
        220435, 262144, 311743, 370727, 440871, 524288, 623487, 741455, 881743, 1048576
      };

  private final int modificationsCounterMax;
  private boolean modificationCounterIsInBytes = true;
  private DirectoryHeader directoryHeader;
  private final Map<Byte, ElementaryFile> efBySfi = new ConcurrentHashMap<Byte, ElementaryFile>();
  private final Map<Byte, ElementaryFile> efBySfiBackup =
      new ConcurrentHashMap<Byte, ElementaryFile>();
  private final Map<Short, Byte> sfiByLid = new ConcurrentHashMap<Short, Byte>();
  private final Map<Short, Byte> sfiByLidBackup = new ConcurrentHashMap<Short, Byte>();
  private Boolean isDfRatified = null;
  private Integer pinAttemptCounter;
  private Integer svBalance;
  private int svLastTNum;
  private SvLoadLogRecord svLoadLogRecord;
  private SvDebitLogRecord svDebitLogRecord;


  private Boolean isBasic() {
    return getApplicationType() == (byte) 0x98 && getApplicationSubtype() == (byte) 0x33;
  }

  /**
   * Constructor.
   *
   * <p>Create the initial content from the data received in response to the card selection.
   *
   * @param cardSelectionResponse the response to the selection application command
   * @since 0.9
   */
  CalypsoPo(CardSelectionResponse cardSelectionResponse) {
    super(cardSelectionResponse);

    int bufferSizeIndicator;
    int bufferSizeValue;

    if (hasFci() && getFciBytes().length > 2) {

      /* Parse PO FCI - to retrieve DF Name (AID), Serial Number, &amp; StartupInfo */
      GetDataFciRespPars poFciRespPars =
          new GetDataFciRespPars(cardSelectionResponse.getSelectionStatus().getFci(), null);

      // 4 fields extracted by the low level parser
      dfName = poFciRespPars.getDfName();
      calypsoSerialNumber = poFciRespPars.getApplicationSerialNumber();
      startupInfo = poFciRespPars.getDiscretionaryData();
      isDfInvalidated = poFciRespPars.isDfInvalidated();

      byte applicationType = getApplicationType();
      byte appSubType = getApplicationSubtype();
      revision = determineRevision(applicationType, appSubType);

      // session buffer size
      bufferSizeIndicator = startupInfo[SI_BUFFER_SIZE_INDICATOR];
      bufferSizeValue = BUFFER_SIZE_INDICATOR_TO_BUFFER_SIZE[bufferSizeIndicator];

      if (revision == PoRevision.REV2_4) {
        /* old cards have their modification counter in number of commands */
        modificationCounterIsInBytes = false;
        modificationsCounterMax = REV2_PO_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION;
      } else if( isBasic() ) { // Calypso Basic
        modificationCounterIsInBytes = false;
        modificationsCounterMax = 4;
      } else {
        modificationsCounterMax = bufferSizeValue;
      }
      isConfidentialSessionModeSupported = !isBasic() && (applicationType & APP_TYPE_CALYPSO_REV_32_MODE) != 0;
      isDeselectRatificationSupported =
          (applicationType & APP_TYPE_RATIFICATION_COMMAND_REQUIRED) == 0;
      isSvFeatureAvailable = (applicationType & APP_TYPE_WITH_CALYPSO_SV) != 0;
      isPinFeatureAvailable = (applicationType & APP_TYPE_WITH_CALYPSO_PIN) != 0;
      isPublicAuthenticationSupported =
          (applicationType & APP_TYPE_WITH_PUBLIC_AUTHENTICATION) != 0;
    } else {
      /*
       * FCI is not provided: we consider it is Calypso PO rev 1, it's serial number is
       * provided in the ATR
       */
      if (!hasAtr()) {
        throw new IllegalStateException(
            "Unable to identify this PO: Neither the CFI nor the ATR are available.");
      }

      byte[] atr = getAtrBytes();
      /* basic check: we expect to be here following a selection based on the ATR */
      if (atr.length != PO_REV1_ATR_LENGTH) {
        throw new IllegalStateException(
            "Unexpected ATR length: " + ByteArrayUtil.toHex(getAtrBytes()));
      }

      revision = PoRevision.REV1_0;
      dfName = null;
      calypsoSerialNumber = new byte[8];
      /* old cards have their modification counter in number of commands */
      modificationCounterIsInBytes = false;
      /*
       * the array is initialized with 0 (cf. default value for primitive types)
       */
      System.arraycopy(atr, 12, calypsoSerialNumber, 4, 4);
      modificationsCounterMax = REV1_PO_DEFAULT_WRITE_OPERATIONS_NUMBER_SUPPORTED_PER_SESSION;

      startupInfo = new byte[7];
      // create buffer size indicator
      startupInfo[0] = (byte) modificationsCounterMax;
      // create the startup info with the 6 bytes of the ATR from position 6
      System.arraycopy(atr, 6, startupInfo, 1, 6);

      // TODO check these flags
      isConfidentialSessionModeSupported = false;
      isDeselectRatificationSupported = true;
      isSvFeatureAvailable = false;
      isPinFeatureAvailable = false;
      isPublicAuthenticationSupported = false;
      isDfInvalidated = false;
    }
    /* Rev1 and Rev2 expects the legacy class byte while Rev3 expects the ISO class byte */
    if (revision == PoRevision.REV1_0 || revision == PoRevision.REV2_4) {
      poClass = PoClass.LEGACY;
    } else {
      poClass = PoClass.ISO;
    }
  }

  /**
   * Resolve the PO revision from the application type byte
   *
   * <ul>
   *   <li>if <code>%1-------</code>&nbsp;&nbsp;&rarr;&nbsp;&nbsp;CLAP&nbsp;&nbsp;&rarr;&nbsp;&
   *       nbsp; REV3.1
   *   <li>if <code>%00101---</code>&nbsp;&nbsp;&rarr;&nbsp;&nbsp;REV3.2
   *   <li>if <code>%00100---</code>&nbsp;&nbsp;&rarr;&nbsp;&nbsp;REV3.1
   *   <li>otherwise&nbsp;&nbsp;&rarr;&nbsp;&nbsp;REV2.4
   * </ul>
   *
   * @param applicationType the application type (field of startup info)
   * @param appSubType
   * @return the {@link PoRevision}
   */
  private PoRevision determineRevision(byte applicationType, byte appSubType) {
    if(applicationType == (byte) 0x98 && appSubType == (byte) 0x33) {
      return PoRevision.REV3_1;
    } else if (((applicationType & 0xFF) & (1 << 7)) != 0) {
      /* CLAP */
      return PoRevision.REV3_1_CLAP;
    } else if ((applicationType >> 3) == (byte) (0x05)) {
      return PoRevision.REV3_2;
    } else if ((applicationType >> 3) == (byte) (0x04)) {
      return PoRevision.REV3_1;
    } else {
      return PoRevision.REV2_4;
    }
  }

  /**
   * Gets the PO revision.
   *
   * <p>The PO revision indicates the generation of the product presented.
   *
   * <p>It will also have an impact on the internal construction of some commands to take into
   * account the specificities of the different POs.
   *
   * @return an enum giving the identified PO revision
   * @since 0.9
   */
  public final PoRevision getRevision() {
    return revision;
  }

  /**
   * Gets the DF name as an array of bytes.
   *
   * <p>The DF name is the name of the application DF as defined in ISO/IEC 7816-4.
   *
   * <p>It also corresponds to the complete representation of the target covered by the AID value
   * provided in the selection command.
   *
   * <p>The AID selects the application by specifying all or part of the targeted DF Name (5 bytes
   * minimum).
   *
   * @return a byte array containing the DF Name bytes (5 to 16 bytes)
   * @since 0.9
   */
  public final byte[] getDfNameBytes() {
    return dfName;
  }

  /**
   * Gets the DF name as an HEX String.
   *
   * @return the DF name as an HEX string (see getDfNameBytes)
   * @since 0.9
   */
  public final String getDfName() {
    return ByteArrayUtil.toHex(getDfNameBytes());
  }

  /**
   * Gets the full Calypso serial number including the possible validity date information in the two
   * MSB.
   *
   * <p>The serial number to be used as diversifier for key derivation.<br>
   * This is the complete number returned by the PO in its response to the Select command.
   *
   * @return a byte array containing the Calypso Serial Number (8 bytes)
   * @since 0.9
   */
  protected final byte[] getCalypsoSerialNumber() {
    return calypsoSerialNumber;
  }

  /**
   * Gets the Calypso application serial number as an array of bytes.
   *
   * <p>The serial number for the application, is unique ID for the PO. <br>
   * The difference with getCalypsoSerialNumber is that the two possible bytes (MSB) of validity
   * date are here forced to zero.
   *
   * @return a byte array containing the Application Serial Number (8 bytes)
   * @since 0.9
   */
  public final byte[] getApplicationSerialNumberBytes() {
    byte[] applicationSerialNumber = calypsoSerialNumber.clone();
    applicationSerialNumber[0] = 0;
    applicationSerialNumber[1] = 0;
    return applicationSerialNumber;
  }

  /**
   * Gets the Calypso application serial number as an HEX String.
   *
   * @return a String representing the Application Serial Number (8 bytes / 16 hex digits)
   * @since 0.9
   */
  public final String getApplicationSerialNumber() {
    return ByteArrayUtil.toHex(getApplicationSerialNumberBytes());
  }

  /**
   * Gets the Calypso startup information field as an HEX String
   *
   * @return the startup info field from the FCI as an HEX string
   * @since 0.9
   */
  public final String getStartupInfo() {
    return ByteArrayUtil.toHex(startupInfo);
  }

  protected final boolean isSerialNumberExpiring() {
    throw new IllegalStateException("Not yet implemented");
  }

  protected final byte[] getSerialNumberExpirationBytes() {
    throw new IllegalStateException("Not yet implemented");
  }

  /**
   * Get the Answer To Reset as an HEX String.
   *
   * <p>The Answer To Reset is sent by the PO is ISO7816-3 mode and in contactless mode for PC/SC
   * readers.
   *
   * <p>When the ATR is obtained in contactless mode, it is in fact reconstructed by the reader from
   * information obtained from the lower communication layers.Therefore, it may differ from one
   * reader to another depending on the interpretation that has been made by the manufacturer of the
   * PC/SC standard.
   *
   * <p>This field is not interpreted in the Calypso module.
   *
   * @return an HEX chain representing the ATR
   * @throws IllegalStateException if the ATR is not available (see {@code hasAtr()} method)
   * @since 0.9
   */
  public final String getAtr() {
    return ByteArrayUtil.toHex(getAtrBytes());
  }

  /**
   * Gets the maximum length of data that an APDU in this PO can carry.
   *
   * @return An int
   * @since 0.9
   */
  protected final int getPayloadCapacity() {
    // TODO make this value dependent on the type of PO identified
    return 250;
  }

  /**
   * Tells if the change counter allowed in session is established in number of operations or number
   * of bytes modified.
   *
   * <p>This varies depending on the revision of the PO.
   *
   * @return true if the counter is number of bytes
   * @since 0.9
   */
  protected final boolean isModificationsCounterInBytes() {
    return modificationCounterIsInBytes;
  }

  /**
   * Indicates the maximum number of changes allowed in session.
   *
   * <p>This number can be a number of operations or a number of commands (see
   * isModificationsCounterInBytes)
   *
   * @return the maximum number of modifications allowed
   * @since 0.9
   */
  protected final int getModificationsCounter() {
    return modificationsCounterMax;
  }

  /**
   * The platform identification byte is the reference of the chip
   *
   * @return the platform identification byte
   * @since 0.9
   */
  public final byte getPlatform() {
    return startupInfo[SI_PLATFORM];
  }

  /**
   * The Application Type byte determines the Calypso Revision and various options
   *
   * @return the Application Type byte
   * @since 0.9
   */
  public final byte getApplicationType() {
    return startupInfo[SI_APPLICATION_TYPE];
  }

  /**
   * Indicates whether the Confidential Session Mode is supported or not (since rev 3.2).
   *
   * <p>This boolean is interpreted from the Application Type byte
   *
   * @return true if the Confidential Session Mode is supported
   * @since 0.9
   */
  public final boolean isConfidentialSessionModeSupported() {
    return isConfidentialSessionModeSupported;
  }

  /**
   * Indicates if the ratification is done on deselect (ratification command not necessary)
   *
   * <p>This boolean is interpreted from the Application Type byte
   *
   * @return true if the ratification command is required
   * @since 0.9
   */
  public final boolean isDeselectRatificationSupported() {
    return isDeselectRatificationSupported;
  }

  /**
   * Indicates whether the PO has the Calypso Stored Value feature.
   *
   * <p>This boolean is interpreted from the Application Type byte
   *
   * @return true if the PO has the Stored Value feature
   * @since 0.9
   */
  public final boolean isSvFeatureAvailable() {
    return isSvFeatureAvailable;
  }

  /**
   * Indicates whether the PO has the Calypso PIN feature.
   *
   * <p>This boolean is interpreted from the Application Type byte
   *
   * @return true if the PO has the PIN feature
   * @since 0.9
   */
  public final boolean isPinFeatureAvailable() {
    return isPinFeatureAvailable;
  }

  /**
   * Indicates whether the Public Authentication is supported or not (since rev 3.3).
   *
   * <p>This boolean is interpreted from the Application Type byte
   *
   * @return true if the Public Authentication is supported
   * @since 0.9
   */
  public final boolean isPublicAuthenticationSupported() {
    return isPublicAuthenticationSupported;
  }

  /**
   * The Application Subtype indicates to the terminal a reference to the file structure of the
   * Calypso DF.
   *
   * @return the Application Subtype byte
   * @since 0.9
   */
  public final byte getApplicationSubtype() {
    return startupInfo[SI_APPLICATION_SUBTYPE];
  }

  /**
   * The Software Issuer byte indicates the entity responsible for the software of the selected
   * application.
   *
   * @return the Software Issuer byte
   * @since 0.9
   */
  public final byte getSoftwareIssuer() {
    return startupInfo[SI_SOFTWARE_ISSUER];
  }

  /**
   * The Software Version field may be set to any fixed value by the Software Issuer of the Calypso
   * application.
   *
   * @return the Software Version byte
   * @since 0.9
   */
  public final byte getSoftwareVersion() {
    return startupInfo[SI_SOFTWARE_VERSION];
  }

  /**
   * The Software Revision field may be set to any fixed value by the Software Issuer of the Calypso
   * application.
   *
   * @return the Software Revision byte
   * @since 0.9
   */
  public final byte getSoftwareRevision() {
    return startupInfo[SI_SOFTWARE_REVISION];
  }

  /**
   * Get the session modification byte from the startup info structure.
   *
   * <p>Depending on the type of PO, the session modification byte indicates the maximum number of
   * bytes that can be modified or the number of possible write commands in a session.
   *
   * @return the Session Modifications byte
   * @since 0.9
   */
  public final byte getSessionModification() {
    return startupInfo[SI_BUFFER_SIZE_INDICATOR];
  }

  /**
   * Tells if the PO has been invalidated or not.
   *
   * <p>An invalidated PO has 6283 as status word in response to the Select Application command.
   *
   * @return true if the PO has been invalidated.
   * @since 0.9
   */
  public final boolean isDfInvalidated() {
    return isDfInvalidated;
  }

  /**
   * Tells if the last session with this PO has been ratified or not.
   *
   * @return true if the PO has been ratified.
   * @throws IllegalStateException if these methods is call when no session has been opened
   * @since 0.9
   */
  public final boolean isDfRatified() {
    if (isDfRatified != null) {
      return isDfRatified;
    }
    throw new IllegalStateException(
        "Unable to determine the ratification status. No session was opened.");
  }

  /**
   * (package-private)<br>
   * Sets the Stored Value data from the SV Get command
   *
   * @param svBalance the current SV balance
   * @param svLastTNum the last SV transaction number
   * @param svLoadLogRecord the SV load log record (may be null if not available)
   * @param svDebitLogRecord the SV debit log record (may be null if not available)
   * @since 0.9
   */
  final void setSvData(
      int svBalance,
      int svLastTNum,
      SvLoadLogRecord svLoadLogRecord,
      SvDebitLogRecord svDebitLogRecord) {
    this.svBalance = svBalance;
    this.svLastTNum = svLastTNum;
    // update logs, do not overwrite existing values (case of double reading)
    if (this.svLoadLogRecord == null) {
      this.svLoadLogRecord = svLoadLogRecord;
    }
    if (this.svDebitLogRecord == null) {
      this.svDebitLogRecord = svDebitLogRecord;
    }
  }

  /**
   * Gets the current SV balance value
   *
   * @return An int
   * @throws IllegalStateException if no SV Get command has been executed
   * @since 0.9
   */
  public final int getSvBalance() {
    if (svBalance == null) {
      throw new IllegalStateException("No SV Get command has been executed.");
    }
    return svBalance;
  }

  /**
   * Gets the last SV transaction number
   *
   * @return An int
   * @throws IllegalStateException if no SV Get command has been executed
   * @since 0.9
   */
  public final int getSvLastTNum() {
    if (svBalance == null) {
      throw new IllegalStateException("No SV Get command has been executed.");
    }
    return svLastTNum;
  }

  /**
   * Gets a reference to the last {@link SvLoadLogRecord}
   *
   * @return a last SV load log record object or null if not available
   * @throws NoSuchElementException if requested log is not found.
   * @since 0.9
   */
  public final SvLoadLogRecord getSvLoadLogRecord() {
    if (svLoadLogRecord == null) {
      // try to get it from the file data
      byte[] logRecord = getFileBySfi(CalypsoPoUtils.SV_RELOAD_LOG_FILE_SFI).getData().getContent();
      svLoadLogRecord = new SvLoadLogRecord(logRecord, 0);
    }
    return svLoadLogRecord;
  }

  /**
   * Gets a reference to the last {@link SvDebitLogRecord}
   *
   * @return a last SV debit log record object or null if not available
   * @throws NoSuchElementException if requested log is not found.
   * @since 0.9
   */
  public final SvDebitLogRecord getSvDebitLogLastRecord() {
    if (svDebitLogRecord == null) {
      // try to get it from the file data
      List<SvDebitLogRecord> svDebitLogRecords = getSvDebitLogAllRecords();
      svDebitLogRecord = svDebitLogRecords.get(0);
    }
    return svDebitLogRecord;
  }

  /**
   * Gets list of references to the {@link SvDebitLogRecord} read from the PO.
   *
   * @return a list of SV debit log record objects or null if not available
   * @throws NoSuchElementException if requested log is not found.
   * @since 0.9
   */
  public final List<SvDebitLogRecord> getSvDebitLogAllRecords() {
    // get the logs from the file data
    SortedMap<Integer, byte[]> logRecords =
        getFileBySfi(CalypsoPoUtils.SV_DEBIT_LOG_FILE_SFI).getData().getAllRecordsContent();
    List<SvDebitLogRecord> svDebitLogRecords = new ArrayList<SvDebitLogRecord>();
    for (Map.Entry<Integer, byte[]> entry : logRecords.entrySet()) {
      svDebitLogRecords.add(new SvDebitLogRecord(entry.getValue(), 0));
    }
    return svDebitLogRecords;
  }

  /**
   * (package-private)<br>
   * Sets the ratification status
   *
   * @param dfRatified true if the session was ratified
   */
  final void setDfRatified(boolean dfRatified) {
    isDfRatified = dfRatified;
  }

  /**
   * The PO class is the ISO7816 class to be used with the current PO.
   *
   * <p>It determined from the PO revision
   *
   * <p>Two classes are possible: LEGACY and ISO.
   *
   * @return the PO class determined from the PO revision
   */
  protected final PoClass getPoClass() {
    return poClass;
  }

  /**
   * Gets the DF metadata.
   *
   * @return null if is not set.
   * @since 0.9
   */
  public final DirectoryHeader getDirectoryHeader() {
    return directoryHeader;
  }

  /**
   * (package-private)<br>
   * Sets the DF metadata.
   *
   * @param directoryHeader the DF metadata (should be not null)
   * @return the current instance.
   */
  final CalypsoPo setDirectoryHeader(DirectoryHeader directoryHeader) {
    this.directoryHeader = directoryHeader;
    return this;
  }

  /**
   * Gets a reference to the {@link ElementaryFile} that has the provided SFI value.<br>
   * Note that if a secure session is actually running, then the object contains all session
   * modifications, which can be canceled if the secure session fails.
   *
   * @param sfi the SFI to search
   * @return a not null reference.
   * @throws NoSuchElementException if requested EF is not found.
   * @since 0.9
   */
  public final ElementaryFile getFileBySfi(byte sfi) {
    ElementaryFile ef = efBySfi.get(sfi);
    if (ef == null) {
      throw new NoSuchElementException(
          "EF with SFI [0x" + Integer.toHexString(sfi & 0xFF) + "] is not found.");
    }
    return ef;
  }

  /**
   * Gets a reference to the {@link ElementaryFile} that has the provided LID value.<br>
   * Note that if a secure session is actually running, then the object contains all session
   * modifications, which can be canceled if the secure session fails.
   *
   * @param lid the LID to search
   * @return a not null reference.
   * @throws NoSuchElementException if requested EF is not found.
   * @since 0.9
   */
  public final ElementaryFile getFileByLid(short lid) {
    Byte sfi = sfiByLid.get(lid);
    if (sfi == null) {
      throw new NoSuchElementException(
          "EF with LID [" + Integer.toHexString(lid & 0xFFFF) + "] is not found.");
    }
    return efBySfi.get(sfi);
  }

  /**
   * Gets a reference to a map of all known Elementary Files by their associated SFI.<br>
   * Note that if a secure session is actually running, then the map contains all session
   * modifications, which can be canceled if the secure session fails.
   *
   * @return a not null reference (may be empty if no one EF is set).
   * @since 0.9
   */
  public final Map<Byte, ElementaryFile> getAllFiles() {
    return efBySfi;
  }

  /**
   * (private)<br>
   * Gets or creates the EF having the provided SFI.
   *
   * @param sfi the SFI
   * @return a not null reference.
   */
  private ElementaryFile getOrCreateFile(byte sfi) {
    ElementaryFile ef = efBySfi.get(sfi);
    if (ef == null) {
      ef = new ElementaryFile(sfi);
      efBySfi.put(sfi, ef);
    }
    return ef;
  }

  /**
   * Indicates if the PIN is blocked. The maximum number of incorrect PIN submissions has been
   * reached.
   *
   * @return true if the PIN status is blocked
   * @throws IllegalStateException if the PIN has not been checked
   * @since 0.9
   */
  public final boolean isPinBlocked() {
    return getPinAttemptRemaining() == 0;
  }

  /**
   * Gives the number of erroneous PIN presentations remaining before blocking.
   *
   * @return the number of remaining attempts
   * @throws IllegalStateException if the PIN has not been checked
   * @since 0.9
   */
  public final int getPinAttemptRemaining() {
    if (pinAttemptCounter == null) {
      throw new IllegalStateException("PIN status has not been checked.");
    }
    return pinAttemptCounter;
  }

  /**
   * (package-private)<br>
   * Sets the PIN attempts counter.<br>
   * The PIN attempt counter is interpreted to give the results of the methods {@link #isPinBlocked}
   * and {@link #getPinAttemptRemaining}.
   *
   * @param pinAttemptCounter the number of remaining attempts to present the PIN code
   */
  final void setPinAttemptRemaining(int pinAttemptCounter) {
    this.pinAttemptCounter = pinAttemptCounter;
  }

  /**
   * (package-private)<br>
   * Sets the provided {@link FileHeader} to the EF having the provided SFI.<br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI
   * @param header the file header (should be not null)
   */
  final void setFileHeader(byte sfi, FileHeader header) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ef.setHeader(header);
    sfiByLid.put(header.getLid(), sfi);
  }

  /**
   * (package-private)<br>
   * Set or replace the entire content of the specified record #numRecord of the provided SFI by the
   * provided content.<br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI
   * @param numRecord the record number (should be {@code >=} 1)
   * @param content the content (should be not empty)
   */
  final void setContent(byte sfi, int numRecord, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ef.getData().setContent(numRecord, content);
  }

  /**
   * (package-private)<br>
   * Sets a counter value in record #1 of the provided SFI.<br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI
   * @param numCounter the counter number (should be {@code >=} 1)
   * @param content the counter value (should be not null and 3 bytes length)
   */
  final void setCounter(byte sfi, int numCounter, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ef.getData().setCounter(numCounter, content);
  }

  /**
   * (package-private)<br>
   * Set or replace the content at the specified offset of record #numRecord of the provided SFI by
   * a copy of the provided content.<br>
   * If EF does not exist, then it is created.<br>
   * If actual record content is not set or has a size {@code <} offset, then missing data will be
   * padded with 0.
   *
   * @param sfi the SFI
   * @param numRecord the record number (should be {@code >=} 1)
   * @param content the content (should be not empty)
   * @param offset the offset (should be {@code >=} 0)
   */
  final void setContent(byte sfi, int numRecord, byte[] content, int offset) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ef.getData().setContent(numRecord, content, offset);
  }

  /**
   * (package-private)<br>
   * Fill the content of the specified #numRecord of the provided SFI using a binary OR operation
   * with the provided content.<br>
   * If EF does not exist, then it is created.<br>
   * If actual record content is not set or has a size {@code <} content size, then missing data
   * will be completed by the provided content.
   *
   * @param sfi the SFI
   * @param numRecord the record number (should be {@code >=} 1)
   * @param content the content (should be not empty)
   */
  final void fillContent(byte sfi, int numRecord, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ef.getData().fillContent(numRecord, content);
  }

  /**
   * (package-private)<br>
   * Add cyclic content at record #1 by rolling previously all actual records contents (record #1 ->
   * record #2, record #2 -> record #3,...) of the provided SFI.<br>
   * This is useful for cyclic files. Note that records are infinitely shifted.<br>
   * <br>
   * If EF does not exist, then it is created.
   *
   * @param sfi the SFI
   * @param content the content (should be not empty)
   */
  final void addCyclicContent(byte sfi, byte[] content) {
    ElementaryFile ef = getOrCreateFile(sfi);
    ef.getData().addCyclicContent(content);
  }

  /**
   * (package-private)<br>
   * Make a backup of the Elementary Files.<br>
   * This method should be used before starting a PO secure session.
   */
  final void backupFiles() {
    copyMapFiles(efBySfi, efBySfiBackup);
    copyMapSfi(sfiByLid, sfiByLidBackup);
  }

  /**
   * (package-private)<br>
   * Restore the last backup of Elementary Files.<br>
   * This method should be used when SW of the PO close secure session command is unsuccessful or if
   * secure session is aborted.
   */
  final void restoreFiles() {
    copyMapFiles(efBySfiBackup, efBySfi);
    copyMapSfi(sfiByLidBackup, sfiByLid);
  }

  /**
   * (private)<br>
   * Copy a map of ElementaryFile by SFI to another one by cloning each element.
   *
   * @param src the source (should be not null)
   * @param dest the destination (should be not null)
   */
  private static void copyMapFiles(Map<Byte, ElementaryFile> src, Map<Byte, ElementaryFile> dest) {
    dest.clear();
    for (Map.Entry<Byte, ElementaryFile> entry : src.entrySet()) {
      dest.put(entry.getKey(), new ElementaryFile(entry.getValue()));
    }
  }

  /**
   * (private)<br>
   * Copy a map of SFI by LID to another one by cloning each element.
   *
   * @param src the source (should be not null)
   * @param dest the destination (should be not null)
   */
  private static void copyMapSfi(Map<Short, Byte> src, Map<Short, Byte> dest) {
    dest.clear();
    dest.putAll(src);
  }
}
