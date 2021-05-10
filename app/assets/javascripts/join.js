/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const println = console.log;

document.addEventListener('DOMContentLoaded', () => {

    for (const tag of document.getElementsByTagName('button')) {
        tag.addEventListener('click', () => {
            if (tag.form.reportValidity()) {
                // add a hidden field indicating which button the user pressed
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = 'action';
                input.value = tag.value;
                tag.form.appendChild(input);

                // disable the button since processing can take a bit
                tag.disabled = true;

                tag.form.submit();
            }
        });
    }

});

