// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <img :src="computedImage" :height="dimensions" :width="dimensions" :style="{ marginTop: (dimensions === 56 || ['deployVirtualMachine'].includes($route.path.split('/')[2])) ? '' : '-5px' }"/>
</template>
<script>
export default {
  name: 'ResourceIcon',
  props: {
    image: {
      type: String,
      required: true
    },
    size: {
      type: String,
      default: '4x'
    }
  },
  data () {
    return {}
  },
  computed: {
    computedImage () {
      if (!this.image) {
        return null
      }
      if (this.image.startsWith('data:image/png')) {
        return this.image
      }
      return 'data:image/png;charset=utf-8;base64, ' + this.image
    },
    dimensions () {
      const num = Number(this.size)
      if (Number.isInteger(num) && num > 0) {
        return num
      }
      switch (this.size) {
        case '4x':
          return 56
        case '2x':
          return 24
        case '1x':
          return 16
        case 'os':
          return 28
        default:
          return 16
      }
    }
  }
}
</script>
