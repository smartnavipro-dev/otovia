# Privacy Policy

**Last Updated**: February 22, 2026
**Effective Date**: December 18, 2025
**Applicable Version**: v1.1.52 and later

---

## 1. Introduction

Otovia (the "App") is an accessibility tool designed to enhance the reading experience for all users, including those with visual impairments.

This Privacy Policy explains what information the App collects and how it is used.

**Important**: The App **stores all learning data exclusively on your device**. For OCR correction, recognized OCR text is sent to Gemini API (Google). See Section 3.2 for details.

---

## 2. Contact Information

- **App Name**: Otovia
- **Developer**: SmartNaviPro Development
- **Contact**: privacy@smartnavipro.dev
- **GitHub**: https://github.com/smartnavipro-dev/otovia

---

## 3. Information We Collect

### 3.1 Information Collected by Learning Feature (v1.1.0+)

When you enable the learning feature, the App stores the following information locally:

#### 📋 Detailed Information Collection

| Information Type | Content | Storage Location | External Transmission |
|-----------------|---------|------------------|----------------------|
| **OCR Text** | Text recognized by ML Kit before correction | Device only | ❌ None |
| **Corrected Text** | Correct text after user correction | Device only | ❌ None |
| **Usage Count** | Number of times each pattern was used | Device only | ❌ None |
| **Last Used Date** | Timestamp when pattern was last used | Device only | ❌ None |

#### 📌 Information We DO NOT Collect

- ❌ Book titles
- ❌ Author names
- ❌ Page numbers
- ❌ Reading history
- ❌ Location data
- ❌ Device identifiers (IMEI, MAC address, etc.)
- ❌ Contact information
- ❌ Any other personally identifiable information

### 3.2 External Services for OCR Correction

The App uses **Gemini API (Google)** to improve OCR correction accuracy.

#### Information Sent to External Services

| Information | Content | Destination | Purpose |
|------------|---------|-------------|---------|
| **OCR Text Only** | Text recognized by ML Kit | Google Gemini API | OCR correction |

#### 🚨 Important Notice

- ✅ **Learning data (user correction history) is NOT sent**
- ✅ Only OCR text is sent (no book titles, page numbers, etc.)
- ⚠️ Google may use this data for machine learning (free tier policy)
- ⚠️ Human reviewers may access this data (per Google's policy)

For details, see "About Gemini API Usage" section.

---

## 4. How We Use Information

### 4.1 Learning Feature (Local Learning)

Collected information is used exclusively for:

| Purpose | Details |
|---------|---------|
| **Improve OCR Accuracy** | Learn frequent error patterns for automatic correction |
| **Personalized Optimization** | Adapt corrections to your reading preferences |
| **Statistics Display** | Show number of learned patterns in the app |

### 4.2 Limitations on Use

- ❌ NOT used for advertising
- ❌ NOT shared with third parties
- ❌ NOT sent to external servers
- ❌ NOT used for commercial purposes

---

## 5. Information Storage

### 5.1 Storage Location

All learning data is stored **exclusively on your Android device**:

```
Location: /data/data/com.otovia.app/shared_prefs/
Filename: user_corrections_encrypted.xml
```

### 5.2 Encryption

Learning data is encrypted using **Android EncryptedSharedPreferences**:

| Security Feature | Details |
|-----------------|---------|
| **Encryption Method** | AES256-GCM |
| **Key Storage** | Android Keystore (hardware-protected) |
| **Backup** | Excluded (not backed up to Google Drive) |

### 5.3 Retention Period

- **Default**: Indefinite (until app is uninstalled)
- **Deletion**: Can be deleted anytime from settings

---

## 6. Third-Party Disclosure

### 6.1 Learning Data Disclosure

**Learning data (user correction history) is NOT shared with third parties.**

- ❌ NOT sent to Google Gemini API
- ❌ NOT sent to any other external services
- ❌ NOT shared even as statistical data

### 6.2 OCR Text Transmission (Gemini API)

For OCR correction, **only uncorrected OCR text** is sent to Gemini API:

| Destination | Content Sent | Purpose | User Control |
|------------|-------------|---------|--------------|
| Google Gemini API | OCR text only | OCR correction | Cannot disable (v1.0.84) |

**Note**: Future versions will include an option to disable Gemini API usage.

---

## 7. User Rights

### 7.1 Withdrawal of Consent

You can withdraw consent for the learning feature at any time:

**Steps**:
1. Open App Settings
2. Turn off "Learning Feature"
3. Tap "Delete Learning Data"

### 7.2 Right to Erasure (GDPR Article 17)

You have the right to delete your learning data at any time:

**Deletion Method**:
```
Settings > Learning Feature > Delete Learning Data
```

**What Gets Deleted**:
- ✅ All correction history
- ✅ Statistics
- ✅ Pattern matching data

**What Remains**:
- App settings (voice speed, volume, etc.)
- OCR correction feature (Gemini API usage)

### 7.3 Right to Access

You can view the number of stored learning patterns in settings:

```
Settings > Learning Feature > Learned Patterns: XX items
```

### 7.4 Data Portability (GDPR Article 20)

Learning data export is not currently available. Planned for future versions.

---

## 8. Security Measures

### 8.1 Technical Measures

| Measure | Details |
|---------|---------|
| **Encryption** | AES256-GCM (EncryptedSharedPreferences) |
| **Key Management** | Android Keystore (hardware-protected) |
| **Backup Exclusion** | Not backed up to Google Drive |
| **Root Detection** | Warning displayed on rooted devices |

### 8.2 Organizational Measures

- Only developers have access to source code
- Open source on GitHub (transparency)
- Security vulnerability reports welcome

### 8.3 Data Breach Response

In the event of a data breach:

1. Notify affected users **within 72 hours**
2. Investigate cause and implement countermeasures
3. Report to relevant authorities (if required)

---

## 9. About Gemini API Usage

### 9.1 Purpose

The App uses Google Gemini API to improve OCR correction accuracy.

### 9.2 Data Sent

- ✅ **OCR text only** (text recognized by ML Kit)
- ❌ **Learning data is NOT sent**
- ❌ **Book titles, authors, page numbers are NOT sent**

### 9.3 Google's Data Usage (Important)

**Gemini API Free Tier Terms**:
> "Google uses the content you submit to the Unpaid Services to improve Google products and develop machine learning technologies."

**This means**:
- ⚠️ Google may use submitted OCR text for machine learning
- ⚠️ Human reviewers may access the data

### 9.4 Future Plans

Future versions will include:

- [ ] Option to disable Gemini API usage
- [ ] Migration to Gemini API paid tier (Google does not use data for training)
- [ ] Fully offline mode

---

## 10. Legal Basis

### 10.1 Japanese Law

The App complies with Japanese law:

| Law | Article | Application |
|-----|---------|-------------|
| **Copyright Act** | Article 30-4 | Reproduction for information analysis (machine learning) |
| **Copyright Act** | Article 37 | Reproduction for persons with disabilities (accessibility) |
| **Personal Information Protection Act** | Article 18 | Disclosure of purpose of use |

### 10.2 GDPR (EU General Data Protection Regulation)

For users in the EU, the App complies with GDPR:

| Right | Implementation |
|-------|---------------|
| **Right to Consent (Article 6)** | Obtained on first launch |
| **Right to Erasure (Article 17)** | Deletable from settings |
| **Right to Rectification (Article 16)** | User can directly edit |
| **Data Portability (Article 20)** | Planned for future |

---

## 11. Children's Privacy

The App is not intended for children under 13 years of age.

If a child under 13 uses the App, parental consent is required.

---

## 12. Privacy Policy Changes

### 12.1 Change Notification

When the Privacy Policy is updated:

1. Update this page
2. Notify within the app (for significant changes)
3. Record change history in GitHub repository

### 12.2 Change History

| Version | Date | Changes |
|---------|------|---------|
| v1.0 | 2025-12-18 | Initial version (v1.1.0 support) |

---

## 13. Contact Us

For privacy-related questions, data deletion requests, or other inquiries:

### Contact Information

- **Privacy Contact**: privacy@smartnavipro.dev
- **General Inquiries**: contact@smartnavipro.dev
- **GitHub Issues**: https://github.com/smartnavipro-dev/otovia/issues

### Response Time

- General: Within 7 business days
- Data deletion requests: Within 72 hours

---

## 14. Disclaimer

### 14.1 Position as Accessibility Tool

The App is an independent accessibility tool separate from Amazon Kindle.

- Not affiliated with Amazon.com or its subsidiaries
- Use of Kindle books subject to Amazon Kindle Terms of Service

### 14.2 OCR Accuracy

- OCR technology has limitations; 100% accuracy not guaranteed
- Learning feature does not guarantee correction of all errors

### 14.3 Security

- Security may be compromised on rooted devices
- Data may be exposed on devices infected with malware

---

## 15. Scope of Application

This Privacy Policy applies to:

- ✅ Otovia Android app (v1.1.0+)
- ✅ Official GitHub repository
- ❌ Modified versions distributed by third parties

---

## 16. Governing Law and Jurisdiction

This Privacy Policy is governed by Japanese law.

Disputes related to the App shall be subject to the exclusive jurisdiction of the Tokyo District Court as the court of first instance.

---

## 📄 Related Documents

- [Terms of Service (Coming Soon)](TERMS_OF_SERVICE.md)
- [Legal Risk Assessment](LEGAL_RISK_ASSESSMENT.md)
- [Implementation Decision Guide](IMPLEMENTATION_DECISION.md)

---

**Last Updated**: February 22, 2026
**Effective Date**: December 18, 2025 (upon v1.1.0 release)
**License**: MIT License

---

**🤖 This Privacy Policy was generated with [Claude Code](https://claude.com/claude-code)**

**Co-Authored-By**: Claude Sonnet 4.5 <noreply@anthropic.com>
