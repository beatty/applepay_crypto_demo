/*
 * Copyright (c) 2012, Axel Nennker - http://axel.nennker.de/
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the names xmldap, xmldap.org, xmldap.com nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.xmldap.crypto;

import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.DerivationFunction;
import org.bouncycastle.crypto.DerivationParameters;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.params.KDFParameters;

/**
 * Basic KDF generator for derived keys and ivs
 * <br>
 * http://csrc.nist.gov/publications/nistpubs/800-56A/SP800-56A_Revision1_Mar08-2007.pdf
 *
 */
public class KDFConcatGenerator
    implements DerivationFunction
{
  private int     counterStart = 1;
  private Digest  digest;
  private byte[]  shared;
  byte[]          otherInfo;

  /**
   * Construct a KDF Parameters generator.
   * <p>
   * @param counterStart value of counter.
   * @param digest the digest to be used as the source of derived keys.
   */
  public KDFConcatGenerator(Digest  digest, byte[] otherInfo)
  {
    this.digest = digest;
    this.otherInfo = otherInfo;
  }

  public void init(
      DerivationParameters    param)
  {
    if (param instanceof KDFParameters)
    {
      KDFParameters   p = (KDFParameters)param;

      shared = p.getSharedSecret();
    }
    else
    {
      throw new IllegalArgumentException("KDF parameters required for KDFConcatGenerator");
    }
  }

  /**
   * return the underlying digest.
   */
  public Digest getDigest()
  {
    return digest;
  }

  /**
   * fill len bytes of the output buffer with bytes generated from
   * the derivation function.
   *
   * @throws IllegalArgumentException if the size of the request will cause an overflow.
   * @throws DataLengthException if the out buffer is too small.
   */
  public int generateBytes(
      byte[] out,
      int    outOff,
      int    len)
      throws DataLengthException, IllegalArgumentException
  {
    if ((out.length - len) < outOff)
    {
      throw new DataLengthException("output buffer too small");
    }

    long    oBytes = len;
    int     outLen = digest.getDigestSize();

    //
    // this is at odds with the standard implementation, the
    // maximum value should be hBits * (2^32 - 1) where hBits
    // is the digest output size in bits. We can't have an
    // array with a long index at the moment...
    //
    if (oBytes > ((2L << 32) - 1))
    {
      throw new IllegalArgumentException("Output length too large");
    }

    int cThreshold = (int)((oBytes + outLen - 1) / outLen);

    byte[] dig = null;

    dig = new byte[digest.getDigestSize()];

    int counter = counterStart;

    for (int i = 0; i < cThreshold; i++)
    {
      // 5.1 Compute Hash_i = H(counter || Z || OtherInfo).
      digest.update((byte)(counter >> 24));
      digest.update((byte)(counter >> 16));
      digest.update((byte)(counter >> 8));
      digest.update((byte)counter);
      digest.update(shared, 0, shared.length);
      digest.update(otherInfo, 0, otherInfo.length);

      digest.doFinal(dig, 0);

      if (len > outLen)
      {
        System.arraycopy(dig, 0, out, outOff, outLen);
        outOff += outLen;
        len -= outLen;
      }
      else
      {
        System.arraycopy(dig, 0, out, outOff, len);
      }

      counter++;
    }

    digest.reset();

    return len;
  }
}

