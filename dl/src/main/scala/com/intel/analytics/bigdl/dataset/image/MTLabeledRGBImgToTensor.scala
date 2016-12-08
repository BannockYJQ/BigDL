/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.dataset.image

import java.util.concurrent.atomic.AtomicInteger

import com.intel.analytics.bigdl.dataset.Transformer
import com.intel.analytics.bigdl.tensor.{Storage, Tensor}
import com.intel.analytics.bigdl.utils.Engine
import scala.reflect.ClassTag

object MTLabeledRGBImgToTensor {
  def apply[A: ClassTag](width: Int, height: Int, threadNum: Int, batchSize: Int,
    transformer: Transformer[A, LabeledRGBImage]): MTLabeledRGBImgToTensor[A] = {
    new MTLabeledRGBImgToTensor[A] (
      width, height, threadNum, batchSize, transformer)
  }
}

class MTLabeledRGBImgToTensor[A: ClassTag](width: Int, height: Int,
  threadNum: Int, batchSize: Int, transformer: Transformer[A, LabeledRGBImage])
  extends Transformer[A, (Tensor[Float], Tensor[Float])] {

  private def getPosition(count : AtomicInteger): Int = {
    val position = count.getAndIncrement()
    if(position < batchSize) position else -1
  }

  private val transformers = (1 to threadNum).map(
    _ => new PreFetch[A] -> transformer.cloneTransformer()
  ).toArray

  private val frameLength = height * width
  private val featureData: Array[Float] = new Array[Float](batchSize * frameLength * 3)
  private val labelData: Array[Float] = new Array[Float](batchSize)
  private val featureTensor: Tensor[Float] = Tensor[Float]()
  private val labelTensor: Tensor[Float] = Tensor[Float]()

  override def apply(prev: Iterator[A]): Iterator[(Tensor[Float], Tensor[Float])] = {
    val iterators = transformers.map(_.apply(prev))

    new Iterator[(Tensor[Float], Tensor[Float])] {
      override def hasNext: Boolean = {
        iterators.map(_.hasNext).reduce(_ || _)
      }

      override def next(): (Tensor[Float], Tensor[Float]) = {
        val count = new AtomicInteger(0)
        Engine.invokeAndWait((0 until threadNum).map(tid => () => {
          var position = 0
          while (iterators(tid).hasNext && {
            position = getPosition(count)
            position != -1
          }) {
            val img = iterators(tid).next()
            img.copyTo(featureData, position * frameLength * 3)
            labelData(position) = img.label()
          }
        }))

        if (labelTensor.nElement() != count.get()) {
          featureTensor.set(Storage[Float](featureData),
            storageOffset = 1, sizes = Array(count.get(), 3, height, width))
          labelTensor.set(Storage[Float](labelData),
            storageOffset = 1, sizes = Array(count.get()))
        }

        (featureTensor, labelTensor)
      }
    }
  }
}

private class PreFetch[T] extends Transformer[T, T] {
  override def apply(prev: Iterator[T]): Iterator[T] = {
    new Iterator[T] {
      private var buffer : T = null.asInstanceOf[T]

      override def hasNext: Boolean = {
        if(buffer != null) {
          true
        } else {
          buffer = prev.next()
          if(buffer == null) false else true
        }
      }

      override def next(): T = {
        if(buffer == null) {
          prev.next()
        } else {
          val tmp = buffer
          buffer = null.asInstanceOf[T]
          tmp
        }
      }
    }
  }
}
