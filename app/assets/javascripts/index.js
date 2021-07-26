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

function clear(parent, maybeChildClassName, removeChild) {
  if (maybeChildClassName === undefined) {
    while (parent.firstChild) {
      parent.removeChild(parent.firstChild);
    }
  }
  else {
    Array.prototype.slice.call(parent.getElementsByClassName(maybeChildClassName)).forEach( child => {
      if (removeChild === true)
        parent.removeChild(child);
      else
        clear(child);
    });
  }
}

document.addEventListener('DOMContentLoaded', () => {
  const body = document.body;
  const title = document.getElementById('title');
  const modal = document.getElementById('modal');
  const message = document.getElementById('message');
  const arena = document.getElementById('arena');
  const scoreboard = document.getElementById('scoreboard');
  const scoresResetImg = document.getElementById('scoresResetImg');
  const scoresResetForm = document.getElementById('scoresResetForm');
  const instructions = document.getElementById('instructions');
  const maybeUpdatesUrl = body.dataset.updatesurl;

  scoresResetForm.addEventListener('submit', event => {
    event.preventDefault();
    const xhr = new XMLHttpRequest();
    xhr.open('post', scoresResetForm.action);
    xhr.send();
  });

  const fixSize = () => {
    if (window.innerWidth > 600) {
      document.documentElement.style.height = `${window.innerHeight}px`;
      body.style.height = `${window.innerHeight}px`;
      modal.style.height = `${window.innerHeight}px`;
    }
    else {
      // if the device rotates, we need to reset these
      document.documentElement.style.height = null;
      body.style.height = null;
      modal.style.height = null;
    }
  };
  window.addEventListener('resize', fixSize);
  fixSize();

  function modalMessage(s) {
    message.innerHTML = s;
    Array.from(document.getElementsByClassName('lds-ring')).forEach(it => it.remove());
  }

  window.setTimeout(() => {
    if (title.innerText === '') {
      modalMessage('Error Loading Arena');
    }
  }, 30 * 1000);

  //body.dataset.paused = 'true';

  if (!!window.EventSource && !!maybeUpdatesUrl) {
    function eventSource() {
      const stringSource = new EventSource(maybeUpdatesUrl);
      stringSource.onopen = () => { };
      stringSource.onmessage = (message) => {
        fixSize();

        if (body.dataset.paused !== 'true') {
          const data = JSON.parse(message.data);
          //console.log(data);

          title.textContent = data.name;

          if (data.joinable) {
            document.getElementById("join").style['display'] = 'inline-block';
          } else {
            document.getElementById("join").style['display'] = 'none';
          }

          if (data.players.length === 0) {
            modal.style.visibility = 'visible';

            if (data.joinable) {
              const joinUrl = window.location.href + '/join';

              const instructionsLink = (data.instructions !== undefined) ? `<a href="${data.instructions}" target="_blank">instructions</a> | ` : '';

              const joinLink = `<a href="${joinUrl}">join</a>`;

              modalMessage(`No players yet. [ ${instructionsLink}${joinLink} ]`);
            } else {
              modalMessage('Arena is closed');
            }
          } else {
            modal.style.visibility = 'hidden';

            if (data.instructions !== undefined) {
              if (data.joinable) {
                instructions.action = data.instructions;
                instructions.style.visibility = 'visible';
              } else {
                instructions.style.visibility = 'hidden';
              }
            }

            if (data.can_reset_in_seconds > 0) {
              scoresResetImg.title = `You can reset the scores in ${data.can_reset_in_seconds} seconds`;
              scoresResetImg.disabled = true;
              scoresResetImg.style.opacity = '0.5';
              scoresResetImg.style.cursor = 'not-allowed';
            } else {
              scoresResetImg.title = `Reset Scores`;
              scoresResetImg.disabled = false;
              scoresResetImg.style.opacity = '1';
              scoresResetImg.style.cursor = 'pointer';
            }

            // arena setup
            if (parseInt(arena.dataset.width) !== data.width || parseInt(arena.dataset.height) !== data.height) {
              // dims changed
              arena.dataset.width = data.width;
              arena.dataset.height = data.height;

              // arena setup
              clear(arena);

              arena.style.gridTemplateColumns = `repeat(${data.width}, 1fr)`;
              arena.style.gridTemplateRows = `repeat(${data.height}, 1fr)`;

              for (let y = 0; y < data.height; y++) {
                for (let x = 0; x < data.width; x++) {
                  const cell = document.createElement('div');
                  cell.id = x + '-' + y;
                  cell.className = 'cell';
                  arena.appendChild(cell);
                }
              }
            }

            clear(arena, 'cell');

            Array.prototype.slice.call(arena.getElementsByClassName('cell')).forEach(cell => {
              cell.className = 'cell'; // reset hits
            });

            clear(scoreboard, 'score', true);

            const sortPlayersByScore = (player1, player2) => player2[1].score - player1[1].score;

            for (const [playerPath, player] of Object.entries(data.players).sort(sortPlayersByScore)) {
              //println(player);

              // profile image on grid
              const cell = document.getElementById(player.x + '-' + player.y);
              const img = document.createElement('div');
              switch (player.direction) {
                case 'N':
                  img.className = 'pic direction-n';
                  break;
                case 'W':
                  img.className = 'pic direction-w';
                  break;
                case 'S':
                  img.className = 'pic direction-s';
                  break;
                case 'E':
                  img.className = 'pic direction-e';
                  break;
              }

              img.style.backgroundImage = `url("${player.pic}")`;

              cell.appendChild(img);

              if (player.wasHit) {
                cell.style.cssText = '--emoji: url(https://noto-website-2.storage.googleapis.com/emoji/emoji_u' + data.emoji_code.toLowerCase() + '.png);';
                cell.className = cell.className + ' hit';
              }

              // scores
              const scoreLine = document.createElement('div');
              scoreLine.className = 'score';

              const scoreImgDiv = document.createElement('div');
              const scoreImg = document.createElement('img');
              scoreImg.src = player.pic;
              scoreImgDiv.appendChild(scoreImg);
              scoreLine.appendChild(scoreImgDiv);

              const name = document.createElement('span');
              name.innerText = player.name;
              name.className = 'name';
              scoreLine.appendChild(name);

              const responseTime = document.createElement('span');
              if (player.responseTimeMS !== undefined) {
                responseTime.innerText = player.responseTimeMS + 'ms';
              } else {
                responseTime.innerText = 'error';
              }
              responseTime.className = 'responseTime';
              scoreLine.appendChild(responseTime);

              const score = document.createElement('span');
              score.innerText = player.score;
              score.className = 'num';
              scoreLine.appendChild(score);

              scoreboard.appendChild(scoreLine);
            }

          }
        }
      };

      stringSource.onerror = (event) => {
        console.error(event);

        // the browser reconnects automatically in some cases, but if it doesn't, then we do it manually
        if (event.target.readyState === EventSource.CLOSED) {
          // try to reconnect
          window.setTimeout(eventSource, 5000);
        }
      };
    }

    eventSource();

  }
  else {
    // todo
  }

});


