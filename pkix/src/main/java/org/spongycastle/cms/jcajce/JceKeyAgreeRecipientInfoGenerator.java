package org.spongycastle.cms.jcajce;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;

import org.spongycastle.asn1.ASN1Encodable;
import org.spongycastle.asn1.ASN1EncodableVector;
import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.ASN1OctetString;
import org.spongycastle.asn1.ASN1Sequence;
import org.spongycastle.asn1.DEROctetString;
import org.spongycastle.asn1.DERSequence;
import org.spongycastle.asn1.cms.KeyAgreeRecipientIdentifier;
import org.spongycastle.asn1.cms.RecipientEncryptedKey;
import org.spongycastle.asn1.cms.RecipientKeyIdentifier;
import org.spongycastle.asn1.cms.ecc.MQVuserKeyingMaterial;
import org.spongycastle.asn1.x509.AlgorithmIdentifier;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.cms.CMSException;
import org.spongycastle.cms.KeyAgreeRecipientInfoGenerator;
import org.spongycastle.jcajce.spec.MQVParameterSpec;
import org.spongycastle.jcajce.spec.UserKeyingMaterialSpec;
import org.spongycastle.operator.DefaultSecretKeySizeProvider;
import org.spongycastle.operator.GenericKey;
import org.spongycastle.operator.SecretKeySizeProvider;

public class JceKeyAgreeRecipientInfoGenerator
    extends KeyAgreeRecipientInfoGenerator
{
    private SecretKeySizeProvider keySizeProvider = new DefaultSecretKeySizeProvider();

    private List recipientIDs = new ArrayList();
    private List recipientKeys = new ArrayList();
    private PublicKey senderPublicKey;
    private PrivateKey senderPrivateKey;

    private EnvelopedDataHelper helper = new EnvelopedDataHelper(new DefaultJcaJceExtHelper());
    private SecureRandom random;
    private KeyPair ephemeralKP;

    public JceKeyAgreeRecipientInfoGenerator(ASN1ObjectIdentifier keyAgreementOID, PrivateKey senderPrivateKey, PublicKey senderPublicKey, ASN1ObjectIdentifier keyEncryptionOID)
    {
        super(keyAgreementOID, SubjectPublicKeyInfo.getInstance(senderPublicKey.getEncoded()), keyEncryptionOID);

        this.senderPublicKey = senderPublicKey;
        this.senderPrivateKey = senderPrivateKey;
    }

    public JceKeyAgreeRecipientInfoGenerator setProvider(Provider provider)
    {
        this.helper = new EnvelopedDataHelper(new ProviderJcaJceExtHelper(provider));

        return this;
    }

    public JceKeyAgreeRecipientInfoGenerator setProvider(String providerName)
    {
        this.helper = new EnvelopedDataHelper(new NamedJcaJceExtHelper(providerName));

        return this;
    }

    public JceKeyAgreeRecipientInfoGenerator setSecureRandom(SecureRandom random)
    {
        this.random = random;

        return this;
    }

    /**
     * Add a recipient based on the passed in certificate's public key and its issuer and serial number.
     * 
     * @param recipientCert recipient's certificate
     * @return the current instance.
     * @throws CertificateEncodingException  if the necessary data cannot be extracted from the certificate.
     */
    public JceKeyAgreeRecipientInfoGenerator addRecipient(X509Certificate recipientCert)
        throws CertificateEncodingException
    {
        recipientIDs.add(new KeyAgreeRecipientIdentifier(CMSUtils.getIssuerAndSerialNumber(recipientCert)));
        recipientKeys.add(recipientCert.getPublicKey());

        return this;
    }

    /**
     * Add a recipient identified by the passed in subjectKeyID and the for the passed in public key.
     *
     * @param subjectKeyID identifier actual recipient will use to match the private key.
     * @param publicKey the public key for encrypting the secret key.
     * @return the current instance.
     * @throws CertificateEncodingException
     */
    public JceKeyAgreeRecipientInfoGenerator addRecipient(byte[] subjectKeyID, PublicKey publicKey)
        throws CertificateEncodingException
    {
        recipientIDs.add(new KeyAgreeRecipientIdentifier(new RecipientKeyIdentifier(subjectKeyID)));
        recipientKeys.add(publicKey);

        return this;
    }

    public ASN1Sequence generateRecipientEncryptedKeys(AlgorithmIdentifier keyAgreeAlgorithm, AlgorithmIdentifier keyEncryptionAlgorithm, GenericKey contentEncryptionKey)
        throws CMSException
    {
        if (recipientIDs.isEmpty())
        {
            throw new CMSException("No recipients associated with generator - use addRecipient()");
        }

        init(keyAgreeAlgorithm.getAlgorithm());

        PrivateKey senderPrivateKey = this.senderPrivateKey;

        ASN1ObjectIdentifier keyAgreementOID = keyAgreeAlgorithm.getAlgorithm();

        ASN1EncodableVector recipientEncryptedKeys = new ASN1EncodableVector();
        for (int i = 0; i != recipientIDs.size(); i++)
        {
            PublicKey recipientPublicKey = (PublicKey)recipientKeys.get(i);
            KeyAgreeRecipientIdentifier karId = (KeyAgreeRecipientIdentifier)recipientIDs.get(i);

            try
            {
                AlgorithmParameterSpec agreementParamSpec;

                if (CMSUtils.isMQV(keyAgreementOID))
                {
                    agreementParamSpec = new MQVParameterSpec(ephemeralKP, recipientPublicKey, null);
                }
                else
                {
                    byte[] ukmKeyingMaterial = ecc_cms_Generator.generateKDFMaterial(keyEncryptionAlgorithm.getAlgorithm(), keySizeProvider.getKeySize(keyEncryptionAlgorithm.getAlgorithm()), null);

                    agreementParamSpec = new UserKeyingMaterialSpec(ukmKeyingMaterial);
                }

                // Use key agreement to choose a wrap key for this recipient
                KeyAgreement keyAgreement = helper.createKeyAgreement(keyAgreementOID);
                keyAgreement.init(senderPrivateKey, agreementParamSpec, random);
                keyAgreement.doPhase(recipientPublicKey, true);

                SecretKey keyEncryptionKey = keyAgreement.generateSecret(keyEncryptionAlgorithm.getAlgorithm().getId());

                // Wrap the content encryption key with the agreement key
                Cipher keyEncryptionCipher = helper.createCipher(keyEncryptionAlgorithm.getAlgorithm());

                keyEncryptionCipher.init(Cipher.WRAP_MODE, keyEncryptionKey, random);

                byte[] encryptedKeyBytes = keyEncryptionCipher.wrap(helper.getJceKey(contentEncryptionKey));

                ASN1OctetString encryptedKey = new DEROctetString(encryptedKeyBytes);

                recipientEncryptedKeys.add(new RecipientEncryptedKey(karId, encryptedKey));
            }
            catch (GeneralSecurityException e)
            {
                throw new CMSException("Cannot perform agreement step: " + e.getMessage(), e);
            }
        }

        return new DERSequence(recipientEncryptedKeys);
    }

    protected ASN1Encodable getUserKeyingMaterial(AlgorithmIdentifier keyAgreeAlg)
        throws CMSException
    {
        init(keyAgreeAlg.getAlgorithm());

        if (ephemeralKP != null)
        {
            return new MQVuserKeyingMaterial(
                        createOriginatorPublicKey(SubjectPublicKeyInfo.getInstance(ephemeralKP.getPublic().getEncoded())), null);
        }

        return null;
    }

    private void init(ASN1ObjectIdentifier keyAgreementOID)
        throws CMSException
    {
        if (random == null)
        {
            random = new SecureRandom();
        }

        if (CMSUtils.isMQV(keyAgreementOID))
        {
            if (ephemeralKP == null)
            {
                try
                {
                    SubjectPublicKeyInfo pubInfo = SubjectPublicKeyInfo.getInstance(senderPublicKey.getEncoded());

                    AlgorithmParameters ecAlgParams = helper.createAlgorithmParameters(keyAgreementOID);

                    ecAlgParams.init(pubInfo.getAlgorithm().getParameters().toASN1Primitive().getEncoded());

                    KeyPairGenerator ephemKPG = helper.createKeyPairGenerator(keyAgreementOID);

                    ephemKPG.initialize(ecAlgParams.getParameterSpec(AlgorithmParameterSpec.class), random);

                    ephemeralKP = ephemKPG.generateKeyPair();
                }
                catch (Exception e)
                {
                    throw new CMSException(
                        "cannot determine MQV ephemeral key pair parameters from public key: " + e, e);
                }
            }
        }
    }

    private static KeyMaterialGenerator ecc_cms_Generator = new RFC5753KeyMaterialGenerator();
}